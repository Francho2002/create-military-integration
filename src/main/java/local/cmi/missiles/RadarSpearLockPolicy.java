package local.cmi.missiles;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Pure deterministic lock selection; callers must supply only currently authorized radar tracks. */
public final class RadarSpearLockPolicy {
    private RadarSpearLockPolicy() {}

    public record Candidate(UUID id, double distanceSquared, long scannedAt) {}
    public record Lock(UUID target, long lastScan, int samples) {
        public static Lock empty() { return new Lock(null, Long.MIN_VALUE, 0); }
    }

    public static Candidate select(UUID current, List<Candidate> candidates) {
        if (current != null) {
            for (Candidate candidate : candidates) if (candidate.id().equals(current)) return candidate;
        }
        return candidates.stream().min(Comparator.comparingDouble(Candidate::distanceSquared)
                .thenComparing(c -> c.id().toString())).orElse(null);
    }

    public static Lock observe(Lock previous, Candidate selected) {
        if (selected == null) return Lock.empty();
        if (!selected.id().equals(previous.target())) return new Lock(selected.id(), selected.scannedAt(), 1);
        if (selected.scannedAt() <= previous.lastScan()) return previous;
        return new Lock(selected.id(), selected.scannedAt(), Math.min(2, previous.samples() + 1));
    }

    public static boolean locked(Lock lock) { return lock.target() != null && lock.samples() >= 2; }
    public static boolean shouldFire(Lock lock, long lastFiredSample) {
        return locked(lock) && lock.lastScan() > lastFiredSample;
    }
    public static boolean fresh(long now, long scannedAt) {
        long age = now - scannedAt;
        return age >= 0 && age <= 1;
    }
}
