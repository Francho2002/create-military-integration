package local.cmi.weapons;

import com.mrvew.firecontrol.ballistics.V3;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Bounded value-only history of observations supplied by a linked physical radar. */
public final class CiwsMotionTracker {
    public static final int SCAN_INTERVAL_TICKS = 5;
    public static final int MAX_SAMPLES = 3;
    public static final int MAX_ENTRIES = 2048;
    public static final long STALE_AFTER_TICKS = 10;

    public record Observer(String dimension, long mountPosition, long radarPosition, UUID shooterOwner, UUID shooterPlatform) {
        public boolean valid() { return dimension != null && !dimension.isBlank() && shooterOwner != null; }
    }

    public record Provenance(UUID owner, UUID platform) {}

    public record Sample(long scannedTime, V3 position, V3 velocity) {
        public boolean finite() {
            return position != null && position.isFinite() && velocity != null && velocity.isFinite();
        }
    }

    public record History(Observer observer, UUID target, Provenance provenance, List<Sample> samples, long lastObservedAt) {
        public History { samples = List.copyOf(samples); }
        public Sample latest() { return samples.isEmpty() ? null : samples.get(samples.size() - 1); }
    }

    public enum Result { FIRST, ADDED, DUPLICATE, DISCONTINUITY, PROVENANCE_RESET, INVALID }
    public record Update(Result result, History history) {}

    private record Key(Observer observer, UUID target) {}
    private record MountKey(String dimension, long mountPosition) {}
    private record ActiveObserver(Observer observer, long lastObservedAt) {}

    private static final class Entry {
        final Provenance provenance;
        final ArrayList<Sample> samples = new ArrayList<>(MAX_SAMPLES);
        long lastObservedAt;
        Entry(Provenance provenance, Sample first, long now) {
            this.provenance = provenance;
            this.samples.add(first);
            this.lastObservedAt = now;
        }
    }

    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(32, 0.75f, true);
    private final LinkedHashMap<MountKey, ActiveObserver> activeObservers = new LinkedHashMap<>(16, 0.75f, true);

    /** Establishes the current radar/owner provenance even when no track is presently authorized. */
    public synchronized boolean beginObservation(Observer observer, long now) {
        if (observer == null || !observer.valid() || now < 0) return false;
        activate(observer, now);
        return true;
    }

    /** One timestamp is one physical sweep; CMI polling frequency never creates extra samples. */
    public synchronized Update observe(Observer observer, UUID target, Provenance provenance,
                                       long scannedTime, V3 position, V3 velocity, long now) {
        if (observer == null || !observer.valid() || target == null || provenance == null || provenance.owner() == null ||
                scannedTime < 0 || now < scannedTime || now < 0) {
            if (observer != null && target != null) entries.remove(new Key(observer, target));
            return new Update(Result.INVALID, null);
        }
        Sample sample = new Sample(scannedTime, position, velocity);
        if (!sample.finite()) {
            entries.remove(new Key(observer, target));
            return new Update(Result.INVALID, null);
        }

        activate(observer, now);
        Key key = new Key(observer, target);
        Entry entry = entries.get(key);
        if (entry == null || !entry.provenance.equals(provenance)) {
            Entry replacement = new Entry(provenance, sample, now);
            entries.put(key, replacement);
            trim();
            return new Update(entry == null ? Result.FIRST : Result.PROVENANCE_RESET, snapshot(key, replacement));
        }

        Sample previous = entry.samples.get(entry.samples.size() - 1);
        if (scannedTime == previous.scannedTime()) {
            if (!sample.position().equals(previous.position()) || !sample.velocity().equals(previous.velocity())) {
                entries.remove(key);
                return new Update(Result.INVALID, null);
            }
            entry.lastObservedAt = now;
            return new Update(Result.DUPLICATE, snapshot(key, entry));
        }
        if (scannedTime < previous.scannedTime()) {
            entries.remove(key);
            return new Update(Result.INVALID, null);
        }
        long delta = scannedTime - previous.scannedTime();
        if (delta != SCAN_INTERVAL_TICKS) {
            Entry replacement = new Entry(provenance, sample, now);
            entries.put(key, replacement);
            return new Update(Result.DISCONTINUITY, snapshot(key, replacement));
        }

        entry.samples.add(sample);
        if (entry.samples.size() > MAX_SAMPLES) entry.samples.remove(0);
        entry.lastObservedAt = now;
        return new Update(Result.ADDED, snapshot(key, entry));
    }

    public synchronized History history(Observer observer, UUID target) {
        if (observer == null || target == null) return null;
        Entry entry = entries.get(new Key(observer, target));
        return entry == null ? null : snapshot(new Key(observer, target), entry);
    }

    /** Removes histories absent from this radar sweep, including lost coverage and vanished entities. */
    public synchronized void retain(Observer observer, Set<UUID> currentlyAuthorizedTracks) {
        if (observer == null) return;
        Set<UUID> keep = currentlyAuthorizedTracks == null ? Set.of() : currentlyAuthorizedTracks;
        entries.keySet().removeIf(key -> key.observer().equals(observer) && !keep.contains(key.target()));
    }

    public synchronized void invalidate(Observer observer, UUID target) {
        if (observer != null && target != null) entries.remove(new Key(observer, target));
    }

    public synchronized void clearMount(String dimension, long mountPosition) {
        if (dimension == null) return;
        entries.keySet().removeIf(key -> key.observer().dimension().equals(dimension) && key.observer().mountPosition() == mountPosition);
        activeObservers.remove(new MountKey(dimension, mountPosition));
    }

    public synchronized void clearChunk(String dimension, ChunkPos chunk) {
        if (dimension == null || chunk == null) return;
        entries.keySet().removeIf(key -> belongsTo(key.observer(), dimension, chunk));
        activeObservers.entrySet().removeIf(entry -> belongsTo(entry.getValue().observer(), dimension, chunk));
    }

    public synchronized void clearDimension(String dimension) {
        if (dimension == null) return;
        entries.keySet().removeIf(key -> key.observer().dimension().equals(dimension));
        activeObservers.keySet().removeIf(key -> key.dimension().equals(dimension));
    }

    public synchronized void prune(long now) {
        entries.entrySet().removeIf(entry -> expired(now, entry.getValue().lastObservedAt));
        activeObservers.entrySet().removeIf(entry -> expired(now, entry.getValue().lastObservedAt()));
    }

    /** Prunes using a world's own game time; dimensions may have independent clocks. */
    public synchronized void prune(String dimension, long now) {
        if (dimension == null || now < 0) return;
        entries.entrySet().removeIf(entry -> entry.getKey().observer().dimension().equals(dimension) &&
                expired(now, entry.getValue().lastObservedAt));
        activeObservers.entrySet().removeIf(entry -> entry.getKey().dimension().equals(dimension) &&
                expired(now, entry.getValue().lastObservedAt()));
    }

    public synchronized int size() { return entries.size(); }

    public synchronized void clear() {
        entries.clear();
        activeObservers.clear();
    }

    private void activate(Observer observer, long now) {
        MountKey key = new MountKey(observer.dimension(), observer.mountPosition());
        ActiveObserver previous = activeObservers.put(key, new ActiveObserver(observer, now));
        if (previous != null && !previous.observer().equals(observer)) {
            entries.keySet().removeIf(entry -> entry.observer().equals(previous.observer()));
        }
        while (activeObservers.size() > MAX_ENTRIES) {
            Iterator<MountKey> iterator = activeObservers.keySet().iterator();
            MountKey eldest = iterator.next();
            ActiveObserver removed = activeObservers.get(eldest);
            iterator.remove();
            if (removed != null) entries.keySet().removeIf(entry -> entry.observer().equals(removed.observer()));
        }
    }

    private static boolean belongsTo(Observer observer, String dimension, ChunkPos chunk) {
        return observer.dimension().equals(dimension) && (inChunk(observer.mountPosition(), chunk) || inChunk(observer.radarPosition(), chunk));
    }

    private static boolean inChunk(long packedPosition, ChunkPos chunk) {
        return new ChunkPos(BlockPos.of(packedPosition)).equals(chunk);
    }

    private static boolean expired(long now, long lastObservedAt) {
        return now < lastObservedAt || now - lastObservedAt > STALE_AFTER_TICKS;
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            Iterator<Key> iterator = entries.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private static History snapshot(Key key, Entry entry) {
        return new History(key.observer(), key.target(), entry.provenance, entry.samples, entry.lastObservedAt);
    }
}
