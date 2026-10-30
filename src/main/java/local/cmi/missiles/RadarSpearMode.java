package local.cmi.missiles;

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
}
