package local.cmi.core;

/** Central destructive permission, deliberately separate from entity recognition. */
public final class NeutralizationPolicy {
    private NeutralizationPolicy() {}

    /**
     * Preserve the pre-existing CBC/Warium whitelist for other namespaces. Every PE
     * namespace ID, including unrecognized future IDs, is governed only by the
     * explicit PE classification permission.
     */
    public static boolean allows(String registryId, boolean legacyProjectile) {
        if (registryId == null || registryId.isBlank()) return false;
        int separator = registryId.indexOf(':');
        if (separator <= 0 || separator == registryId.length() - 1) return false;
        if (ProjectExplosiveCompatibility.isProjectExplosiveRegistryId(registryId))
            return ProjectExplosiveCompatibility.classifyRegistryId(registryId).neutralizable();
        return legacyProjectile;
    }

    /**
     * Acquisition is an independent, opt-in policy for the two conventional PE bombs.
     * Recognition, unknown ownership, stale radar data, unsafe territory, or an unknown
     * detonation state can never produce a CIWS candidate.
     */
    public static boolean allowsConventionalBombAcquisition(String registryId, boolean optIn,
            boolean physicalTrackCurrent, boolean entityLive, boolean playerOwnerVerified,
            boolean metadataConsistent, Relation relation, boolean protectedTarget,
            boolean detonationStateKnown, boolean impactOrDetonationStarted) {
        if (!isConventionalBombId(registryId) || !optIn || !physicalTrackCurrent || !entityLive ||
                !playerOwnerVerified || !metadataConsistent || relation != Relation.ENEMY || protectedTarget ||
                !detonationStateKnown || impactOrDetonationStarted) return false;
        return true;
    }

    /** Destructive permission is issued only by the managed CBC physical-hit callback. */
    public static boolean allowsConventionalBombNeutralization(String registryId, boolean optIn,
            boolean physicalTrackCurrent, boolean entityLive, boolean playerOwnerVerified,
            boolean metadataConsistent, Relation relation, boolean protectedTarget,
            boolean detonationStateKnown, boolean impactOrDetonationStarted, boolean managedCbcShot,
            boolean physicalCollisionConfirmed, boolean oneUsePermitConsumed) {
        return allowsConventionalBombAcquisition(registryId, optIn, physicalTrackCurrent, entityLive,
                playerOwnerVerified, metadataConsistent, relation, protectedTarget,
                detonationStateKnown, impactOrDetonationStarted) && managedCbcShot &&
                physicalCollisionConfirmed && oneUsePermitConsumed;
    }

    private static boolean isConventionalBombId(String registryId) {
        if (registryId == null) return false;
        ProjectExplosiveCompatibility.Kind kind = ProjectExplosiveCompatibility.classifyRegistryId(registryId).kind();
        return kind.conventionalBomb();
    }
}
