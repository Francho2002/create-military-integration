package local.cmi.mixin.weapons;

import net.ato.shupapium.cannons.MountedShupapiumACContraption;
import net.ato.shupapium.cannons.ShupapiumACBreechBlockEntity;
import net.ato.shupapium.utils.actypes.ShupapiumACLayout;
import net.ato.shupapium.utils.actypes.ShupapiumACProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import rbasamoyai.createbigcannons.cannons.autocannon.material.AutocannonMaterial;

@Mixin(value = MountedShupapiumACContraption.class, remap = false)
public interface WariumCannonAccess {
    @Accessor("profile") ShupapiumACProfile cmi$profile();
    @Accessor("cannonMaterial") AutocannonMaterial cmi$material();
    @Invoker("getLayout") ShupapiumACLayout cmi$layout();
    @Invoker("hasIncompatibleAmmo") boolean cmi$incompatible(ShupapiumACBreechBlockEntity breech);
}
