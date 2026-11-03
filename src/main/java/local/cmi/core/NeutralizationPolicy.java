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
}
