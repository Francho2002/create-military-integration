package local.cmi.weapons;

import java.util.ArrayList;
import java.util.Optional;
import local.cmi.mixin.weapons.LauncherMaterialAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMunitionsLauncherContraption;
import riftyboi.cbcmodernwarfare.cannons.munitions_contraption_launcher.IMunitionsLauncherBlockEntity;
import riftyboi.cbcmodernwarfare.cannons.munitions_contraption_launcher.MunitionsLauncherBehavior;
import riftyboi.cbcmodernwarfare.munitions.munitions_contraption_launcher.guidance.MunitionsLauncherGuidanceBlock;
import riftyboi.cbcmodernwarfare.munitions.munitions_contraption_launcher.guidance.infrared_homing.InfraredSeekerGuidanceBlock;
import riftyboi.cbcmodernwarfare.munitions.munitions_contraption_launcher.propulsion.MunitionsLauncherThrusterBlock;

/** Powered launcher metadata from loaded blocks and its native material. */
public final class LauncherSupport {
    private LauncherSupport() {}
    public record Profile(float initialSpeed, double spawnDistance, InfraredSeekerGuidanceBlock seeker,
                          MunitionsLauncherBehavior guidanceBore, StructureBlockInfo guidanceInfo) {}
    public static boolean isLauncher(PitchOrientedContraptionEntity entity) {
        return entity.getContraption() instanceof MountedMunitionsLauncherContraption;
    }
    /** Only a single IR seeker and an inert complete warhead are accepted. Native fireShot validates the full assembly. */
    public static Optional<Profile> inspect(ServerLevel level, PitchOrientedContraptionEntity entity) {
        if (!(entity.getContraption() instanceof MountedMunitionsLauncherContraption cannon) || cannon.getStartPos() == null) return Optional.empty();
        var material = ((LauncherMaterialAccess)cannon).cmi$material();
        if (material == null) return Optional.empty();
        var warhead = new ArrayList<StructureBlockInfo>();
        MunitionsLauncherBehavior guidanceBore = null;
        StructureBlockInfo guidanceInfo = null;
        InfraredSeekerGuidanceBlock seeker = null;
        boolean thruster = false, complete = false;
        BlockPos end = cannon.getStartPos().relative(cannon.initialOrientation().getOpposite());
        var breech = cannon.presentBlockEntities.get(end);
        if (breech instanceof riftyboi.cbcmodernwarfare.cannons.munitions_contraption_launcher.breeches.quickfiring.LauncherQuickFiringBreechBlockEntity quick && quick.getOpenProgress() > 0) return Optional.empty();
        BlockPos pos = breech instanceof riftyboi.cbcmodernwarfare.cannons.munitions_contraption_launcher.breeches.IBreechBlockEntity ? cannon.getStartPos() : end;
        int i;
        for (i = 0; i < 256 && cannon.presentBlockEntities.get(pos) instanceof IMunitionsLauncherBlockEntity bore; i++, pos = pos.relative(cannon.initialOrientation())) {
            var behavior = (MunitionsLauncherBehavior)bore.cannonBehavior();
            var info = behavior.block();
            var block = info.state().getBlock();
            if (block instanceof BigCannonPropellantBlock && !(block instanceof ProjectileBlock)) return Optional.empty();
            if (block instanceof MunitionsLauncherThrusterBlock) thruster = true;
            if (block instanceof MunitionsLauncherGuidanceBlock) {
                if (seeker != null || !(block instanceof InfraredSeekerGuidanceBlock ir) || info.nbt() == null) return Optional.empty();
                seeker = ir; guidanceBore = behavior; guidanceInfo = info;
            }
            if (block instanceof ProjectileBlock projectile) {
                if (complete) return Optional.empty();
                warhead.add(info);
                if (projectile.isComplete(warhead, cannon.initialOrientation())) {
                    if (!WeaponAdapters.inert(projectile.getProjectile(level, warhead))) return Optional.empty();
                    complete = true;
                }
            }
        }
        if (i == 256 || !complete || !thruster || seeker == null) return Optional.empty();
        var dir = cannon.initialOrientation();
        double along = pos.getX()*dir.getStepX() + pos.getY()*dir.getStepY() + pos.getZ()*dir.getStepZ();
        return Optional.of(new Profile(material.properties().initialSpeed(), Math.max(0, along - 1), seeker, guidanceBore, guidanceInfo));
    }
}
