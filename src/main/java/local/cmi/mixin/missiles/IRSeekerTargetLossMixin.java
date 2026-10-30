package local.cmi.mixin.missiles;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Let the native seeker reacquire after its tracked object is gone. */
@Mixin(targets = "net.ato.shupapium.entities.IRSeekerBCProjectile", remap = false)
public abstract class IRSeekerTargetLossMixin {
    @Shadow private Entity target;

    @Inject(method = "tick", at = @At("HEAD"))
    private void cmi$forgetLostTarget(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!self.level().isClientSide && target != null && (target.isRemoved() || !target.isAlive())) target = null;
    }
}
