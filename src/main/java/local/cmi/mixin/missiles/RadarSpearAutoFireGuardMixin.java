package local.cmi.mixin.missiles;

import local.cmi.missiles.RadarSpearAutomation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents redstone/native entry points bypassing IFF while auto mode owns this hardpoint. */
@Mixin(targets = "com.wariumce.procedures.MissileHardpointFireProcedure", remap = false)
public abstract class RadarSpearAutoFireGuardMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cmi$requireAuthorizedAutoRequest(LevelAccessor world, double x, double y, double z, BlockState state, CallbackInfo ci) {
        BlockPos pos = BlockPos.containing(x, y, z);
        if (RadarSpearAutomation.controls(world, pos) && !RadarSpearAutomation.authorizedNativeFire(world, pos)) ci.cancel();
    }
}
