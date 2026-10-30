package local.cmi.missiles;

import net.minecraft.nbt.CompoundTag;

public final class RadarSpearModeRegression {
    public static void main(String[] args) throws Exception {
        CompoundTag nativeMissile = new CompoundTag();
        require(!RadarSpearMode.managed(nativeMissile));
        nativeMissile.putUUID("cmiOwner", java.util.UUID.randomUUID());
        require(!RadarSpearMode.managed(nativeMissile.copy()));
        require(RadarSpearMode.launchMode(nativeMissile) == RadarSpearMode.LaunchMode.NATIVE);
        CompoundTag launcher = new CompoundTag();
        launcher.putBoolean("cmiManaged", true);
        require(RadarSpearMode.launchMode(launcher) == RadarSpearMode.LaunchMode.REQUESTED);
        require(!RadarSpearMode.hasDesignation(launcher));
        launcher.putDouble("TargetX", 1); launcher.putDouble("TargetY", 2); launcher.putDouble("TargetZ", 3);
        require(RadarSpearMode.hasDesignation(launcher.copy()));
        launcher.putDouble("TargetZ", Double.NaN);
        require(!RadarSpearMode.hasDesignation(launcher));
        launcher.putString("cmiManaged", "malformed");
        require(RadarSpearMode.launchMode(launcher) == RadarSpearMode.LaunchMode.INVALID);
        CompoundTag managed = new CompoundTag();
        managed.putBoolean("cmiManaged", true);
        var target = java.util.UUID.randomUUID();
        managed.putUUID("cmiTargetId", target);
        managed.putString("cmiTargetKind", "ENTITY");
        require(RadarSpearMode.managed(managed.copy()) && managed.copy().getUUID("cmiTargetId").equals(target));
        managed.putString("cmiManaged", "invalid");
        require(RadarSpearMode.managed(managed));
        managed.putBoolean("cmiManaged", false);
        require(!RadarSpearMode.managed(managed));
        managed.putString("cmiRadarImpact", "broken");
        require(RadarSpearMode.managed(managed));
        managed.remove("cmiManaged");
        require(RadarSpearMode.managed(managed));
        String bridge = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/missiles/RadarSpearBridge.java"));
        require(bridge.contains("if (target.isEmpty() || !CombatServices.canEngage(level, actor, target.get())) return false"));
        require(bridge.contains("filter(c -> c.bounds().contains(point))"));
        require(bridge.contains("RadarSpearMode.managed(t) && !validManaged(level, missile)"));
        require(bridge.indexOf("RadarSpearImpact.arm(t, actor") < bridge.indexOf("t.putBoolean(\"cmiManaged\", true)"));
        String hook = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/mixin/missiles/RadarSpearLaunchMixin.java"));
        require(hook.contains("ordinal = 3") && hook.contains("captureLaunch(server, projectile, pos)"));
        String entityHook = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/mixin/missiles/RadarSpearEntityMixin.java"));
        require(entityHook.contains("RadarSpearBridge.preflight(server, self)"));
        require(!bridge.contains("b.putDouble(\"TargetX\""));
        System.out.println("Radar Spear mode regression checks passed");
    }
    private static void require(boolean value) {
        if (!value) throw new AssertionError("Radar Spear mode regression");
    }
}
