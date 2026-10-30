package local.cmi.mixin.weapons;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMunitionsLauncherContraption;
import riftyboi.cbcmodernwarfare.cannons.munitions_contraption_launcher.material.MunitionsLauncherMaterial;
@Mixin(value = MountedMunitionsLauncherContraption.class, remap = false)
public interface LauncherMaterialAccess {
    @Accessor("cannonMaterial") MunitionsLauncherMaterial cmi$material();
}
