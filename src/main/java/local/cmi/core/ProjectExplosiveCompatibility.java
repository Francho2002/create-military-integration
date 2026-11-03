package local.cmi.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

/** Optional, registry-ID-only recognition for Project: Explosive. Recognition grants no combat authority. */
public final class ProjectExplosiveCompatibility {
    private ProjectExplosiveCompatibility() {}

    public enum Kind {
        SMALL_BOMB,
        BIG_BOMB,
        FIRE_BOMB,
        NUCLEAR_BOMB,
        ICBM,
        ICBM_BOOSTER,
        SMOKETRAIL,
        FIRE_TRAIL,
        MULTI_BLOCK,
        UNKNOWN;

        public boolean conventionalBomb() { return this == SMALL_BOMB || this == BIG_BOMB; }
    }

    public enum Role {
        FUTURE_INTEROPERABILITY_CANDIDATE,
        SUPPORT_ENTITY_EXCLUDED_FROM_CIWS,
        UNKNOWN
    }

    public record Classification(Kind kind, Role role) {
        public boolean recognized() { return kind != Kind.UNKNOWN; }
        /** No Project: Explosive type is authorized for automated target acquisition in this cycle. */
        public boolean acquirable() { return false; }
        /** No Project: Explosive type is authorized for neutralization in this cycle. */
        public boolean neutralizable() { return false; }
        public boolean excludedFromCiws() { return role == Role.SUPPORT_ENTITY_EXCLUDED_FROM_CIWS; }
    }

    private static final Classification UNKNOWN = new Classification(Kind.UNKNOWN, Role.UNKNOWN);
    private static final Role CANDIDATE = Role.FUTURE_INTEROPERABILITY_CANDIDATE;
    private static final Role SUPPORT = Role.SUPPORT_ENTITY_EXCLUDED_FROM_CIWS;
    private static final Classification SMALL_BOMB = new Classification(Kind.SMALL_BOMB, CANDIDATE);
    private static final Classification BIG_BOMB = new Classification(Kind.BIG_BOMB, CANDIDATE);
    private static final Classification FIRE_BOMB = new Classification(Kind.FIRE_BOMB, CANDIDATE);
    private static final Classification NUCLEAR_BOMB = new Classification(Kind.NUCLEAR_BOMB, CANDIDATE);
    private static final Classification ICBM = new Classification(Kind.ICBM, CANDIDATE);
    private static final Classification ICBM_BOOSTER = new Classification(Kind.ICBM_BOOSTER, SUPPORT);
    private static final Classification SMOKETRAIL = new Classification(Kind.SMOKETRAIL, SUPPORT);
    private static final Classification FIRE_TRAIL = new Classification(Kind.FIRE_TRAIL, SUPPORT);
    private static final Classification MULTI_BLOCK = new Classification(Kind.MULTI_BLOCK, SUPPORT);

    /** Exact IDs verified in Project: Explosive 0.7.2-beta's entity-type registry. */
    public static Classification classifyRegistryId(String registryId) {
        if (registryId == null) return UNKNOWN;
        return switch (registryId) {
            case "projectexplosive:small_bomb" -> SMALL_BOMB;
            case "projectexplosive:big_bomb" -> BIG_BOMB;
            case "projectexplosive:fire_bomb" -> FIRE_BOMB;
            case "projectexplosive:nuclear_bomb" -> NUCLEAR_BOMB;
            case "projectexplosive:icbm" -> ICBM;
            case "projectexplosive:icbm_booster" -> ICBM_BOOSTER;
            case "projectexplosive:smoketrail" -> SMOKETRAIL;
            case "projectexplosive:fire_trail" -> FIRE_TRAIL;
            case "projectexplosive:multi_block" -> MULTI_BLOCK;
            default -> UNKNOWN;
        };
    }

    public static boolean isProjectExplosiveRegistryId(String registryId) {
        if (registryId == null) return false;
        int separator = registryId.indexOf(':');
        return separator > 0 && "projectexplosive".equals(registryId.substring(0, separator));
    }

    public static Classification classify(Entity entity) {
        if (entity == null) return UNKNOWN;
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? UNKNOWN : classifyRegistryId(id.toString());
    }

    public static boolean isProjectExplosive(Entity entity) {
        if (entity == null) return false;
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && isProjectExplosiveRegistryId(id.toString());
    }

    /** Fail closed for every entity in this optional mod's namespace, including unlisted future IDs. */
    public static boolean blocksAutomaticCiwsRegistryId(String registryId) {
        if (registryId == null) return true;
        int separator = registryId.indexOf(':');
        if (separator <= 0 || separator == registryId.length() - 1) return true;
        return "projectexplosive".equals(registryId.substring(0, separator));
    }

    public static boolean blocksAutomaticCiws(Entity entity) {
        if (entity == null) return true;
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null) return true;
        String registryId = id.toString();
        return classifyRegistryId(registryId).recognized() || blocksAutomaticCiwsRegistryId(registryId);
    }
}
