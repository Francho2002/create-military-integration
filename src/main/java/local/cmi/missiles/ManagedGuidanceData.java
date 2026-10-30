package local.cmi.missiles;

import java.util.Optional;
import local.cmi.core.TargetId;
import net.minecraft.nbt.CompoundTag;

/** The explicit guidance opt-in and target data stored in the seeker's persistent NBT. */
public final class ManagedGuidanceData {
    public static final String MARKER = "cmiManagedGuidance";
    public static final String DESIGNATED = "cmiDesignated";
    public static final String INTERCEPTOR = "cmiInterceptor";
    public static final String TARGET_ID = "cmiTargetId";
    public static final String TARGET_KIND = "cmiTargetKind";

    private ManagedGuidanceData() {}

    public static boolean managed(CompoundTag data) {
        if (!validMode(data)) return true;
        return GuidanceMode.managed(data.getBoolean(MARKER), data.getBoolean(DESIGNATED), data.getBoolean(INTERCEPTOR));
    }

    public static boolean validMode(CompoundTag data) {
        for (String key : new String[]{MARKER, DESIGNATED, INTERCEPTOR}) {
            // A malformed mode marker is ambiguous: keep control rather than fall back to native targeting.
            if (data.contains(key) && !data.contains(key, net.minecraft.nbt.Tag.TAG_BYTE)) return false;
        }
        return true;
    }

    public static Optional<TargetId> target(CompoundTag data) {
        if (!data.hasUUID(TARGET_ID)) return Optional.empty();
        try {
            return Optional.of(new TargetId(TargetId.Kind.valueOf(data.getString(TARGET_KIND)), data.getUUID(TARGET_ID)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Preserve the managed state even if legacy mode fields are later removed. */
    public static void clearTarget(CompoundTag data) {
        // Preserve malformed explicit marker for fail-closed validation; otherwise
        // latch even if a legacy designation/interceptor flag has a malformed type.
        if (managed(data) && (!data.contains(MARKER) || data.contains(MARKER, net.minecraft.nbt.Tag.TAG_BYTE))) {
            data.putBoolean(MARKER, true);
        }
        data.remove(TARGET_ID);
        data.remove(TARGET_KIND);
    }
}
