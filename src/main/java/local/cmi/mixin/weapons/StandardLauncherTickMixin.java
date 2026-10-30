package local.cmi.mixin.weapons;

import local.cmi.weapons.LauncherCoordinator;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

@Mixin(value = CannonMountBlockEntity.class, remap = false)
public abstract class StandardLauncherTickMixin {
    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void cmi$launcherTick(CallbackInfo ci) {
        var mount = (CannonMountBlockEntity)(Object)this;
        if (mount.getLevel() instanceof ServerLevel level && mount.isRunning() && mount.getPersistentData().hasUUID("cmiOwner"))
            LauncherCoordinator.tick(level, mount.getContraption(), mount);
    }
}
