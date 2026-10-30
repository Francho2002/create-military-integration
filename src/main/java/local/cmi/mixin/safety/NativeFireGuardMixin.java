package local.cmi.mixin.safety;

import com.happysg.radar.block.behavior.networks.WeaponFiringControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=WeaponFiringControl.class, remap=false)
public abstract class NativeFireGuardMixin {
    @Shadow private void stopFireCannon() { throw new AssertionError(); }
    @Inject(method="tryFireCannon", at=@At("HEAD"), cancellable=true, remap=false)
    private void cmi$guard(CallbackInfo ci) { if (!FireAuthorization.permits(this)) { stopFireCannon(); ci.cancel(); } }
}
