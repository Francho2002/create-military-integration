package local.cmi.missiles;

import net.minecraft.nbt.CompoundTag;

public final class RadarSpearModeRegression {
    public static void main(String[] args) throws Exception {
        CompoundTag nativeMissile = new CompoundTag();
        require(RadarSpearMode.beginDecision(nativeMissile));
        require(!RadarSpearMode.beginDecision(nativeMissile));
        require(!RadarSpearMode.managed(nativeMissile));
        nativeMissile.putUUID("cmiOwner", java.util.UUID.randomUUID());
        require(!RadarSpearMode.managed(nativeMissile.copy()));
        CompoundTag managed = new CompoundTag();
        managed.putBoolean("cmiManaged", true);
        var target = java.util.UUID.randomUUID();
        managed.putUUID("cmiTargetId", target);
        managed.putString("cmiTargetKind", "ENTITY");
        CompoundTag restored = managed.copy();
        require(RadarSpearMode.managed(restored) && restored.getUUID("cmiTargetId").equals(target));
        require(RadarSpearMode.managed(managed));
        require(!RadarSpearMode.beginDecision(managed));
        String bridge = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/missiles/RadarSpearBridge.java"));
        int rejection = bridge.indexOf("if (target.isEmpty()) { missile.discard(); return; }");
        int adoption = bridge.indexOf("t.putBoolean(\"cmiManaged\", true)");
        require(rejection >= 0 && adoption > rejection);
        require(bridge.contains("CombatServices.canEngage(level, actor, c)"));
        require(bridge.contains("if (!validManaged(level, t)) missile.discard()"));
        require(bridge.contains("CombatServices.resolve(level, id).filter(target -> CombatServices.canEngage(level, actor, target))"));
        require(bridge.contains("CombatServices.neutralize(level, actor, target)"));
        require(!bridge.contains("TankFireProjectileHitsBlockProcedure"));
        String hook = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/mixin/missiles/RadarSpearEntityMixin.java"));
        require(hook.contains("if (self.isRemoved()) ci.cancel()"));
        require(hook.contains("RadarSpearMode.managed(self.getPersistentData())"));
        String launcher = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/weapons/LauncherSupport.java"));
        require(launcher.contains("!WeaponAdapters.inert(projectile.getProjectile(level, warhead))"));
        System.out.println("Radar Spear mode regression checks passed");
        managed.putString("cmiManaged", "invalid");
        require(RadarSpearMode.managed(managed));
        require(!RadarSpearMode.beginDecision(managed));
    }
    private static void require(boolean value) {
        if (!value) throw new AssertionError("Radar Spear mode regression");
    }
}
