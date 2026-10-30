package local.cmi.missiles;

import local.cmi.core.TargetId;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Only an explicit marker selects managed behavior; malformed markers fail closed. */
public final class RadarSpearMode {
    public enum LaunchMode { NATIVE, REQUESTED, INVALID }
    private RadarSpearMode() {}
    public static boolean managed(CompoundTag data) {
        return data.contains("cmiRadarImpact") || (data.contains("cmiManaged") && (!data.contains("cmiManaged", Tag.TAG_BYTE) || data.getBoolean("cmiManaged")));
    }
    public static LaunchMode launchMode(CompoundTag source) {
        if (!source.contains("cmiManaged")) return LaunchMode.NATIVE;
        if (!source.contains("cmiManaged", Tag.TAG_BYTE)) return LaunchMode.INVALID;
        return source.getBoolean("cmiManaged") ? LaunchMode.REQUESTED : LaunchMode.NATIVE;
    }
    public static boolean hasDesignation(CompoundTag source) {
        for (String key : new String[]{"TargetX", "TargetY", "TargetZ"}) {
            if (!source.contains(key, 99) || !Double.isFinite(source.getDouble(key))) return false;
        }
        return true;
    }

    /** Resolve a launcher's optional explicit warhead profile without changing legacy defaults. */
    public static String launchProfile(CompoundTag source, TargetId target, boolean projectile) {
        if (source == null || target == null || target.kind() == null || target.uuid() == null) return null;
        if (!source.contains("cmiRadarProfile")) {
            if (projectile) return target.kind() == TargetId.Kind.ENTITY ? "interceptor" : null;
            return target.kind() == TargetId.Kind.SABLE ? "direct_sable" : "direct_living_base";
        }
        if (!source.contains("cmiRadarProfile", Tag.TAG_STRING)) return null;
        String profile = source.getString("cmiRadarProfile");
        boolean compatible = switch (profile) {
            case "direct_sable", "explosive_sable" -> !projectile && target.kind() == TargetId.Kind.SABLE;
            case "direct_living_base" -> !projectile && target.kind() == TargetId.Kind.ENTITY;
            case "interceptor" -> projectile && target.kind() == TargetId.Kind.ENTITY;
            default -> false;
        };
        return compatible ? profile : null;
    }
}
