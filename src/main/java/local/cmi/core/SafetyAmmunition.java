package local.cmi.core;
import net.minecraft.core.registries.BuiltInRegistries;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
/** Explicit conservative allow-list. Explosive shell safe radii have not been established. */
public final class SafetyAmmunition {
    private SafetyAmmunition(){}
    public static boolean kinetic(PitchOrientedContraptionEntity e){
        return local.cmi.weapons.WeaponAdapters.kinetic(e);
    }
}
