package local.cmi.weapons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure, bounded ordering and timeout policy for independent CIWS mounts. */
public final class CiwsCoordinationPolicy {
    /** Two physical sweeps; enough to renew at each five-tick radar update without a long lease. */
    public static final int PREPARING_RESERVATION_TTL_TICKS = 10;
    /** Eight radar sweeps allow lock accumulation and barrel rotation before yielding the target. */
    public static final long PREPARING_BUDGET_TICKS = 40;
    /** Two sweeps give another mount time to acquire before this mount retries the deferred target. */
    public static final long RETRY_COOLDOWN_TICKS = 10;

    private static final Comparator<CiwsPolicy.Candidate> PRIORITY = Comparator
            .comparingDouble(CiwsPolicy.Candidate::closestTime)
            .thenComparingDouble(CiwsPolicy.Candidate::distanceSquared)
            .thenComparing(candidate -> candidate.id().toString());

    private CiwsCoordinationPolicy() {}

    /** Keeps a usable old target first, then returns unique candidates in deterministic order. */
    public static List<CiwsPolicy.Candidate> orderedCandidates(UUID current, List<CiwsPolicy.Candidate> candidates,
                                                               UUID deferred, int maximum) {
        if (candidates == null || maximum <= 0) return List.of();
        Map<UUID, CiwsPolicy.Candidate> unique = new HashMap<>();
        for (CiwsPolicy.Candidate candidate : candidates) {
            if (!valid(candidate) || candidate.id().equals(deferred)) continue;
            unique.merge(candidate.id(), candidate,
                    (left, right) -> PRIORITY.compare(left, right) <= 0 ? left : right);
        }

        List<CiwsPolicy.Candidate> ordered = new ArrayList<>(Math.min(maximum, unique.size()));
        CiwsPolicy.Candidate retained = current == null ? null : unique.remove(current);
        if (retained != null) ordered.add(retained);
        List<CiwsPolicy.Candidate> alternatives = new ArrayList<>(unique.values());
        alternatives.sort(PRIORITY);
        for (CiwsPolicy.Candidate candidate : alternatives) {
            if (ordered.size() == maximum) break;
            ordered.add(candidate);
        }
        return List.copyOf(ordered);
    }

    public static boolean preparationExpired(long startedAt, long now) {
        return startedAt < 0 || now < 0 || now < startedAt || now - startedAt >= PREPARING_BUDGET_TICKS;
    }

    public static boolean retryDeferred(UUID target, long retryUntil, long now) {
        return target != null && (now < 0 || retryUntil < 0 || now < retryUntil);
    }

    public static long retryUntil(long now) {
        if (now < 0) return 0;
        if (now > Long.MAX_VALUE - RETRY_COOLDOWN_TICKS) return Long.MAX_VALUE;
        return now + RETRY_COOLDOWN_TICKS;
    }

    private static boolean valid(CiwsPolicy.Candidate candidate) {
        return candidate != null && candidate.id() != null && candidate.scannedAt() >= 0 &&
                Double.isFinite(candidate.closestTime()) && candidate.closestTime() > 0 &&
                Double.isFinite(candidate.distanceSquared()) && candidate.distanceSquared() >= 0;
    }
}
