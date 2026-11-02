package local.cmi.mixin.weapons;

import com.happysg.radar.block.radar.behavior.RadarScanningBlockBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the physical radar category switches used by the CIWS gate. */
@Mixin(value = RadarScanningBlockBehavior.class, remap = false)
public interface RadarScanningFilterAccess {
    @Accessor(value = "scanContraptions", remap = false)
    boolean cmi$scanContraptions();

    @Accessor(value = "scanProjectiles", remap = false)
    boolean cmi$scanProjectiles();
}
