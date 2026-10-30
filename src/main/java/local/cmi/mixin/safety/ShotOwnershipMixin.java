package local.cmi.mixin.safety;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import local.cmi.core.*;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
/** Attribution applies equally to manual and automatic native weapon launches. */
@Mixin(targets={
 "rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption",
 "rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption",
 "riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption",
 "riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedRotarycannonContraption",
 "riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMunitionsLauncherContraption"
},remap=false)
public abstract class ShotOwnershipMixin {
    @WrapMethod(method="fireShot")
    private void cmi$shot(ServerLevel level,PitchOrientedContraptionEntity carrier,Operation<Void> original){
        ShotContext.push(CombatServices.actor(level,carrier));
        try{original.call(level,carrier);}finally{ShotContext.pop();}
    }
}
