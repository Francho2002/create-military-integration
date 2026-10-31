package local.cmi.mixin.missiles;

import local.cmi.missiles.RadarSpearAutomation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives explicitly auto-enabled Radar Spear blocks to CMI before Warium copies controller targets. */
@Mixin(targets = "com.wariumce.procedures.HardpointTriggerTickProcedure", remap = false)
public abstract class RadarSpearAutoTriggerMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cmi$runRadarAutomation(LevelAccessor world, double x, double y, double z, BlockState state, CallbackInfo ci) {
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!RadarSpearAutomation.controls(world, pos)) return;
        RadarSpearAutomation.tick(world, pos);
        ci.cancel();
    }
}
