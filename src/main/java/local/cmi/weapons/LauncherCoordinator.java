package local.cmi.weapons;

import java.util.List;
import com.mrvew.firecontrol.cannon.CannonProbe;
import com.mrvew.firecontrol.control.ComputerFireControl;
import com.mrvew.firecontrol.ship.Ships;
import local.cmi.core.CombatServices;
import local.cmi.core.TrajectorySafety;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import riftyboi.cbcmodernwarfare.cannon_control.compact_mount.CompactCannonMountBlockEntity;
import riftyboi.cbcmodernwarfare.munitions.munitions_contraption_launcher.guidance.infrared_homing.InfraredSeekerGuidanceBlockEntity;

/** Native powered launch; does not approximate powered flight with a ballistic solver. */
public final class LauncherCoordinator {
    private LauncherCoordinator() {}
    public static void tick(ServerLevel level, PitchOrientedContraptionEntity entity, BlockEntity mount) {
        if (entity == null || entity.isStalled() || !LauncherSupport.isLauncher(entity)) return;
        if (!(mount instanceof CompactCannonMountBlockEntity) && !(mount instanceof CannonMountBlockEntity)) return;
        var loaded = LauncherSupport.inspect(level, entity);
        if (loaded.isEmpty()) return;
        var profile = loaded.get();
        if (!Float.isFinite(profile.initialSpeed()) || profile.initialSpeed() < 0) return;
        var actor = CombatServices.actor(level, mount.getBlockPos());
        if (actor == null || actor.owner() == null) return;
        var aim = CannonProbe.aim(level, entity);
        Vec3 pivot = Ships.vec(aim.pivot());
        Vec3 barrel = Ships.vec(aim.barrel());
        var ship = Ships.containing(level, mount.getBlockPos());
        boolean interceptor = mount.getPersistentData().getBoolean("cmiInterceptor");
        for (var snapshot : CombatServices.contacts(level)) {
            var current = CombatServices.resolve(level, snapshot.id());
            if (current.isEmpty()) continue;
            var contact = current.get();
            if (!CombatServices.canEngage(level, actor, contact)) continue;
            if (interceptor ? !contact.projectile() || contact.velocity().dot(contact.position().subtract(pivot)) >= 0 : !contact.hot()) continue;
            Vec3 offset = contact.position().subtract(pivot);
            double distance = offset.length();
            double range = profile.seeker().getRange();
            if (!(distance > 0) || !Double.isFinite(range) || range <= 0 || distance > range) continue;
            Vec3 worldDir = offset.scale(1 / distance);
            Vec3 localDir = Ships.toShipFrame(ship, worldDir, 0);
            double[] angles = ComputerFireControl.mountAngles(localDir);
            if (angles[1] > entity.maximumElevation() || angles[1] < -entity.maximumDepression()) continue;
            if (mount instanceof CompactCannonMountBlockEntity compact) {
                if (!entity.canBeTurnedByController(compact)) return;
                compact.setYaw((float)angles[0]); compact.setPitch((float)angles[1]);
            } else if (mount instanceof CannonMountBlockEntity standard) {
                if (!entity.canBeTurnedByController(standard)) return;
                standard.setYaw((float)angles[0]); standard.setPitch((float)angles[1]);
            }
            if (barrel.dot(worldDir) < 0.99999) return;
            if (barrel.dot(worldDir) < Math.cos(Math.toRadians(profile.seeker().lockFov() / 2))) return;
            Vec3 spawn = pivot.add(barrel.scale(profile.spawnDistance()));
            if (!TrajectorySafety.clear(level, actor, List.of(spawn, contact.position()), 0.5, entity)) return;
            var info = profile.guidanceInfo();
            var blockEntity = BlockEntity.loadStatic(info.pos(), info.state(), info.nbt().copy(), level.registryAccess());
            if (!(blockEntity instanceof InfraredSeekerGuidanceBlockEntity)) return;
            var data = blockEntity.getPersistentData();
            data.putBoolean("cmiDesignated", true);
            data.putBoolean("cmiInterceptor", interceptor);
            data.putUUID("cmiTargetId", contact.id().uuid());
            data.putString("cmiTargetKind", contact.id().kind().name());
            var updated = new StructureBlockInfo(info.pos(), info.state(), blockEntity.saveWithFullMetadata(level.registryAccess()));
            var bore = profile.guidanceBore();
            bore.loadBlock(updated);
            try {
                if (CombatServices.canEngage(level, actor, contact)) WeaponAdapters.fire(level, entity);
            } finally {
                // Native firing clears consumed blocks. Restore only when it rejected the launch.
                if (bore.block() == updated) bore.loadBlock(info);
            }
            return;
        }
    }
}
