package local.cmi.mixin.weapons;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedRotarycannonContraption;
import riftyboi.cbcmodernwarfare.cannons.rotarycannon.material.RotarycannonMaterial;

@Mixin(value = MountedRotarycannonContraption.class, remap = false)
public interface RotaryMaterialAccess {
    @Accessor("cannonMaterial") RotarycannonMaterial cmi$material();
}
