package local.cmi.weapons;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import local.cmi.core.Relation;

/** Pure policy; the caller supplies only live radar contacts that passed CMI authorization. */
public final class CiwsPolicy {
    private CiwsPolicy() {}

    public record Candidate(UUID id, long scannedAt, double closestTime, double distanceSquared) {}
    public record Lock(UUID target, long lastScan, int samples) {
        public static Lock empty() { return new Lock(null, Long.MIN_VALUE, 0); }
    }

    public static final class Once {
        private boolean consumed;
        public boolean consume() { if (consumed) return false; consumed = true; return true; }
    }

    public static boolean eligible(boolean alive, boolean projectile, UUID shooter, UUID owner,
                                   UUID platform, UUID targetPlatform, Relation relation, boolean canEngage) {
        return alive && projectile && shooter != null && owner != null && !shooter.equals(owner) &&
                (platform == null || !platform.equals(targetPlatform)) && relation == Relation.ENEMY && canEngage;
    }

    public static UUID projectileId(boolean projectileTrack, String id, long now, long scannedAt) {
        if (!projectileTrack || id == null || (scannedAt != now && (now == Long.MIN_VALUE || scannedAt != now - 1))) return null;
        try {
            UUID parsed = UUID.fromString(id);
            return parsed.toString().equals(id) ? parsed : null;
        } catch (IllegalArgumentException badId) {
            return null;
        }
    }

    /** Time to closest approach in ticks, with the origin at the defended mount. */
    public static double approaching(double x, double y, double z, double vx, double vy, double vz) {
        if (!finite(x, y, z, vx, vy, vz)) return Double.NaN;
        double speedSquared = vx * vx + vy * vy + vz * vz;
        double radial = x * vx + y * vy + z * vz;
        if (!Double.isFinite(speedSquared) || !Double.isFinite(radial) || speedSquared <= 1.0e-12 || radial >= 0) return Double.NaN;
        double time = -radial / speedSquared;
        return Double.isFinite(time) && time > 0 ? time : Double.NaN;
    }

    /** Sphere wholly inside the current target AABB; solver miss is a center-to-center distance. */
    public static double tolerance(double xSize, double ySize, double zSize) {
        if (!finite(xSize, ySize, zSize) || xSize <= 0 || ySize <= 0 || zSize <= 0) return Double.NaN;
        double radius = Math.nextDown(Math.min(xSize, Math.min(ySize, zSize)) * 0.5);
        return Double.isFinite(radius) && radius > 0 ? radius : Double.NaN;
    }

    public static boolean acceptableSolution(boolean directionFinite, boolean withinArc, double miss,
                                             double tolerance, double time, int lifetime) {
        return directionFinite && withinArc && Double.isFinite(miss) && Double.isFinite(tolerance) &&
                tolerance > 0 && miss >= 0 && miss <= tolerance && Double.isFinite(time) && time > 0 &&
                lifetime > 0 && time <= lifetime;
    }

    public static boolean withinBudget(int count, int maximum) { return count >= 0 && count <= maximum; }

    public static Candidate select(UUID current, List<Candidate> candidates) {
        if (current != null) for (Candidate c : candidates) if (current.equals(c.id())) return c;
        return candidates.stream().min(Comparator.comparingDouble(Candidate::closestTime)
                .thenComparingDouble(Candidate::distanceSquared)
                .thenComparing(c -> c.id().toString())).orElse(null);
    }

    public static Lock observe(Lock previous, Candidate selected) {
        if (selected == null) return Lock.empty();
        if (!selected.id().equals(previous.target())) return new Lock(selected.id(), selected.scannedAt(), 1);
        if (selected.scannedAt() <= previous.lastScan()) return previous;
        if (selected.scannedAt() - previous.lastScan() != 5) return new Lock(selected.id(), selected.scannedAt(), 1);
        return new Lock(selected.id(), selected.scannedAt(), Math.min(2, previous.samples() + 1));
    }

    public static boolean shouldFire(Lock lock, long lastFiredSample) {
        return lock.target() != null && lock.samples() >= 2 && lock.lastScan() > lastFiredSample;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
