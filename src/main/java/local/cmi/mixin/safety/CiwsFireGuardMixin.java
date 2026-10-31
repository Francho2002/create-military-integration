package local.cmi.mixin.safety;

import local.cmi.weapons.CiwsCoordinator;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/** Rotary also calls fireShot from its own tick; a controlled CIWS requires the scoped authorization. */
@Mixin(targets = {
        "riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption",
        "riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedRotarycannonContraption",
        "rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption"
}, remap = false)
public abstract class CiwsFireGuardMixin {
    @Inject(method = "fireShot", at = @At("HEAD"), cancellable = true, remap = false)
    private void cmi$requireCiwsPermit(ServerLevel level, PitchOrientedContraptionEntity cannon, CallbackInfo ci) {
        if (!CiwsCoordinator.permitsNativeFire(level, cannon)) ci.cancel();
    }
}
