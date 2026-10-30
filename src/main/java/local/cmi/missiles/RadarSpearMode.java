package local.cmi.missiles;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Persistent one-time decision, made before the native arrow can collide. */
public final class RadarSpearMode {
    private static final String DECIDED = "cmiRadarModeDecided";
    private RadarSpearMode() {}

    public static boolean managed(CompoundTag data) {
        // An invalid explicit marker must not reactivate native damage.
        return data.contains("cmiManaged") && (!data.contains("cmiManaged", Tag.TAG_BYTE) || data.getBoolean("cmiManaged"));
    }

    public static boolean beginDecision(CompoundTag data) {
        if (managed(data) || data.contains(DECIDED)) return false;
        data.putBoolean(DECIDED, true);
        return true;
    }
}
