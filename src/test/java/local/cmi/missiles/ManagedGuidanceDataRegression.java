package local.cmi.missiles;

import java.util.UUID;
import local.cmi.core.TargetId;
import net.minecraft.nbt.CompoundTag;

/** Exercises real NBT tags in a Java process without starting a client or server. */
public final class ManagedGuidanceDataRegression {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var nbt = new CompoundTag();
        check(!ManagedGuidanceData.managed(nbt), "empty tag is native");
        nbt.putUUID("cmiOwner", UUID.randomUUID());
        check(!ManagedGuidanceData.managed(nbt), "attribution-only owner is native");
        for (String flag : new String[]{ManagedGuidanceData.MARKER, ManagedGuidanceData.DESIGNATED, ManagedGuidanceData.INTERCEPTOR}) {
            var malformed = new CompoundTag(); malformed.putString(flag,"invalid");
            check(ManagedGuidanceData.managed(malformed), "malformed marker remains managed: " + flag);
            check(!ManagedGuidanceData.validMode(malformed), "malformed mode must fail closed: " + flag);
            malformed.putIntArray("cmiTargetId", new int[]{1,2,3});
            check(ManagedGuidanceData.target(malformed).isEmpty(), "malformed target rejected while managed");
            ManagedGuidanceData.clearTarget(malformed);
            check(ManagedGuidanceData.managed(malformed) && !ManagedGuidanceData.validMode(malformed), "malformed mode still fails closed after loss: " + flag);
            if (!flag.equals(ManagedGuidanceData.MARKER)) {
                malformed.remove(flag);
                check(ManagedGuidanceData.managed(malformed), "malformed legacy mode remains latched after repair: " + flag);
            }
            var disabled = new CompoundTag(); disabled.putBoolean(flag,false);
            check(!ManagedGuidanceData.managed(disabled), "explicit false stays native: " + flag);
        }
        nbt.putString("cmiTargetId", "bad");
        nbt.putString("cmiTargetKind", "SABLE");
        check(ManagedGuidanceData.target(nbt).isEmpty(), "string UUID rejected");
        nbt.putIntArray("cmiTargetId", new int[]{1,2,3});
        check(ManagedGuidanceData.target(nbt).isEmpty(), "short UUID rejected");
        UUID id = UUID.randomUUID();
        nbt.putUUID("cmiTargetId", id);
        nbt.putString("cmiTargetKind", "NOT_A_KIND");
        check(ManagedGuidanceData.target(nbt).isEmpty(), "unknown kind rejected");
        nbt.putInt("cmiTargetKind", 1);
        check(ManagedGuidanceData.target(nbt).isEmpty(), "non-string kind rejected");
        nbt.putString("cmiTargetKind", "SABLE");
        check(ManagedGuidanceData.target(nbt).orElseThrow().equals(new TargetId(TargetId.Kind.SABLE,id)), "valid target");
        for (String legacy : new String[]{ManagedGuidanceData.DESIGNATED, ManagedGuidanceData.INTERCEPTOR}) {
            var managed = nbt.copy(); managed.putBoolean(legacy,true);
            check(ManagedGuidanceData.managed(managed), "legacy flag recognized");
            var copied = managed.copy();
            check(ManagedGuidanceData.target(copied).equals(ManagedGuidanceData.target(managed)), "target copied");
            ManagedGuidanceData.clearTarget(copied);
            copied.remove(legacy);
            check(ManagedGuidanceData.managed(copied), "mode latched after loss and legacy removal");
            check(ManagedGuidanceData.target(copied).isEmpty(), "target cleared");
            check(ManagedGuidanceData.managed(copied.copy()), "marker survives NBT copy");
        }
        var nativeCopy = nbt.copy(); ManagedGuidanceData.clearTarget(nativeCopy);
        check(!ManagedGuidanceData.managed(nativeCopy), "clearing native tag does not opt it in");
        System.out.println("ManagedGuidanceData NBT checks passed");
    }
}
