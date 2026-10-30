package local.cmi.mixin.missiles;

import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invokes Neo Warfare's inherited turn model on GuidanceBlockEntity. */
@Mixin(targets = "riftyboi.cbcmodernwarfare.munitions.munitions_contraption_launcher.guidance.GuidanceBlockEntity", remap = false)
public interface GuidanceTurningSpeedInvoker {
    @Invoker(value = "calculateTurningSpeed", remap = false)
    double cmi$calculateTurningSpeed(double size, double speed, BlockState state);
}
