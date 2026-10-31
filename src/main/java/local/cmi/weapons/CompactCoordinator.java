package local.cmi.weapons;

import java.util.ArrayList;
import com.mrvew.firecontrol.ballistics.FiringSolver;
import com.mrvew.firecontrol.ballistics.TargetMotion;
import com.mrvew.firecontrol.ballistics.V3;
import com.mrvew.firecontrol.cannon.CannonProbe;
import com.mrvew.firecontrol.control.ComputerFireControl;
import com.mrvew.firecontrol.ship.Ships;
import local.cmi.core.CombatServices;
import local.cmi.core.TrajectorySafety;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import riftyboi.cbcmodernwarfare.cannon_control.compact_mount.CompactCannonMountBlockEntity;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonAmmoItem;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.ap.APMediumcannonProjectile;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.apds.APDSMediumcannonProjectile;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.apfsds.APFSDSMediumcannonProjectile;

/** Server-only controller for claimed compact mounts. Unsupported trajectories fail closed. */
public final class CompactCoordinator {
    private CompactCoordinator() {}
    public static void tick(ServerLevel level, CompactCannonMountBlockEntity mount) {
        // Explicit false stops CMI automation; an absent flag preserves the previous general path.
        if (CiwsCoordinator.hasConfiguredMode(mount)) {
            if (CiwsCoordinator.controls(mount)) CiwsCoordinator.tick(level, mount);
            return;
        }
        var entity = WeaponAdapters.compactContraption(mount);
        if (entity == null || entity.isStalled() || !entity.canBeTurnedByController(mount)) return;
        if (LauncherSupport.isLauncher(entity)) { LauncherCoordinator.tick(level, entity, mount); return; }
        var ship = Ships.containing(level, mount.getBlockPos());
        var actor = CombatServices.actor(level, mount.getBlockPos());
        if (actor == null) return;
        if (!WeaponAdapters.kinetic(entity)) return;
        var loadout = CannonProbe.loadout(level, entity);
        var shell = loadout.shell();
        if (!(shell.muzzleSpeed() > 0)) return;
        var aim = CannonProbe.aim(level, entity);
        for (var snapshot : CombatServices.contacts(level)) {
            var current = CombatServices.resolve(level, snapshot.id());
            if (current.isEmpty()) continue;
            var contact = current.get();
            if (!CombatServices.canEngage(level, actor, contact)) continue;
            Vec3 target = contact.position();
            Vec3 targetVelocity = contact.velocity();
            var motion = new TargetMotion(new V3(target.x, target.y, target.z), new V3(targetVelocity.x, targetVelocity.y, targetVelocity.z), V3.ZERO);
            Vec3 pivot = Ships.vec(aim.pivot());
            Vec3 muzzle = pivot.add(Ships.vec(aim.barrel()).scale(shell.spawnDistance()));
            Vec3 inherited = ship == null || !shell.inheritVelocity() ? Vec3.ZERO : Ships.velocityAt(level, ship, muzzle);
            var solver = new FiringSolver(shell, aim.pivot(), Ships.v3(inherited), motion, 0, 200);
            var solution = solver.solve(FiringSolver.Arc.LOW, null, 0.25);
            if (solution == null || !solution.direction().isFinite() || !Double.isFinite(solution.miss()) || solution.miss() > 0.25) continue;
            var dir = solution.direction();
            Vec3 localDir = Ships.toShipFrame(ship, Ships.vec(dir), 0);
            var angles = ComputerFireControl.mountAngles(localDir);
            if (angles[1] > entity.maximumElevation() || angles[1] < -entity.maximumDepression()) continue;
            WeaponAdapters.aimCompact(mount, (float) angles[0], (float) angles[1]);
            // Wait for the mount's native tick to apply rotation; recompute all checks next tick.
            if (aim.barrel().dot(dir) < 0.99999) return;
            var path = new ArrayList<Vec3>();
            path.add(new Vec3(aim.pivot().x(), aim.pivot().y(), aim.pivot().z()));
            for (var point : solver.path(dir, solution.flightTicks())) path.add(new Vec3(point.x(), point.y(), point.z()));
            if (CombatServices.canEngage(level, actor, contact) && TrajectorySafety.clear(level, actor, path, 0.5, entity))
                WeaponAdapters.fire(level, entity);
            return;
        }
    }
}
