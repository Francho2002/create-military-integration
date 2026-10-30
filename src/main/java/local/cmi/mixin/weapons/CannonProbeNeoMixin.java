package local.cmi.mixin.weapons;

import com.mrvew.firecontrol.ballistics.ShellModel;
import com.mrvew.firecontrol.cannon.CannonProbe;
import local.cmi.weapons.WeaponAdapters;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonAmmoItem;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedRotarycannonContraption;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonAmmoItem;

@Mixin(value = CannonProbe.class, remap = false)
public abstract class CannonProbeNeoMixin {
    @Inject(method = "loadout", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cmi$neoLoadout(ServerLevel level, PitchOrientedContraptionEntity entity, CallbackInfoReturnable<CannonProbe.Loadout> result) {
        if (!(entity.getContraption() instanceof MountedMediumcannonContraption) &&
            !(entity.getContraption() instanceof MountedRotarycannonContraption)) return;
        var velocity = WeaponAdapters.neoVelocity(entity);
        if (velocity.isEmpty()) {
            result.setReturnValue(new CannonProbe.Loadout(new ShellModel(0, 0, 0, false, 0, false, 0), "", false, 0));
            return;
        }
        var v = velocity.get();
        AbstractCannonProjectile projectile = null;
        if (v.ammunition().getItem() instanceof MediumcannonAmmoItem medium)
            projectile = medium.getMediumcannonProjectile(v.ammunition(), level);
        else if (v.ammunition().getItem() instanceof AutocannonAmmoItem rotary)
            projectile = rotary.getAutocannonProjectile(v.ammunition(), level);
        if (projectile == null) {
            result.setReturnValue(new CannonProbe.Loadout(new ShellModel(0, 0, 0, false, 0, false, 0), "", false, 0));
            return;
        }
        var bp = ((ProjectileBallisticsAccess) projectile).cmi$ballistics();
        if (bp == null) {
            result.setReturnValue(new CannonProbe.Loadout(new ShellModel(0, 0, 0, false, 0, false, 0), "", false, 0));
            return;
        }
        var dim = DimensionMunitionPropertiesHandler.getProperties(level);
        boolean inherit = entity.getContraption() instanceof MountedRotarycannonContraption;
        var shell = new ShellModel(v.blocksPerTick(), bp.gravity() * dim.gravityMultiplier(), bp.drag() * dim.dragMultiplier(), bp.isQuadraticDrag(), v.spawnDistance(), inherit, v.lifetimeTicks());
        result.setReturnValue(new CannonProbe.Loadout(shell, v.ammunition().getHoverName().getString(), false, -1));
    }
}
