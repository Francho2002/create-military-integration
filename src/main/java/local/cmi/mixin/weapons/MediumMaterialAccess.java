package local.cmi.mixin.weapons;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.material.MediumcannonMaterial;

@Mixin(value = MountedMediumcannonContraption.class, remap = false)
public interface MediumMaterialAccess {
    @Accessor("cannonMaterial") MediumcannonMaterial cmi$material();
}
