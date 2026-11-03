package local.cmi.core;

import java.util.UUID;

/** Pure immutable identity and lifetime contract for one experimental PE impact permit. */
public final class ProjectExplosivePermitPolicy {
    private ProjectExplosivePermitPolicy() {}
    public static final long MAX_TTL_TICKS = 200L;

    /** Contains only value identities; no world, entity, block entity, or client-supplied NBT references. */
    public record Identity(String dimension, UUID target, UUID targetOwner, UUID targetPlatform,
            long mountPosition, long radarPosition, String radarType, UUID radarInstance,
            UUID radarOwner, String radarIff, UUID radarPlatform, UUID cannon,
            UUID actorOwner, String actorIff, UUID actorPlatform, String relation, boolean protectedTarget) {
        public Identity {
            radarIff = radarIff == null ? "" : radarIff;
            actorIff = actorIff == null ? "" : actorIff;
        }

        public boolean complete() {
            return dimension != null && !dimension.isBlank() && target != null && targetOwner != null &&
                    radarType != null && !radarType.isBlank() && radarInstance != null && radarOwner != null &&
                    cannon != null && actorOwner != null && "ENEMY".equals(relation) && !protectedTarget;
        }
    }

    /** Exact equality is intentional: any owner, platform, sensor, cannon, actor, or dimension change revokes. */
    public static boolean sameIdentity(Identity issued, Identity current) {
        return issued != null && current != null && issued.complete() && current.complete() && issued.equals(current);
    }

    public static boolean valid(Identity issued, Identity current, boolean optIn,
            boolean consumed, long issuedAt, long expiresAt, long now) {
        return optIn && !consumed && sameIdentity(issued, current) && issuedAt >= 0 &&
                expiresAt >= issuedAt && expiresAt - issuedAt <= MAX_TTL_TICKS &&
                now >= issuedAt && now <= expiresAt;
    }

    /** One physical impact permit can only be associated with exactly one CBC entity join per fire scope. */
    public static boolean uniqueCbcSpawn(int observedSpawns) { return observedSpawns == 1; }

    /** Tiny one-shot token used by the physical callback; a failed callback cannot be retried. */
    public static final class OneShot {
        private boolean consumed;
        public synchronized boolean consume() {
            if (consumed) return false;
            consumed = true;
            return true;
        }
        public synchronized boolean consumed() { return consumed; }
    }
}
