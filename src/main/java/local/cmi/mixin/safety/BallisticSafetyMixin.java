package local.cmi.mixin.safety;
import com.happysg.radar.block.behavior.networks.WeaponFiringControl;
import com.mrvew.firecontrol.ballistics.*;
import com.mrvew.firecontrol.cannon.CannonProbe;
import com.mrvew.firecontrol.control.*;
import com.mrvew.firecontrol.block.BallisticComputerBlockEntity;
import com.mrvew.firecontrol.ship.Ships;
import local.cmi.core.*;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
/** Every actual fire request receives a new corridor/IFF check, regardless of third-party clear-path config. */
@Mixin(value=ComputerFireControl.class,remap=false)
public abstract class BallisticSafetyMixin {
    @Redirect(method="run",at=@At(value="INVOKE",target="Lcom/mrvew/firecontrol/control/FireControlAccess;fc$fire(Z)V"),remap=false)
    private static void cmi$gate(FireControlAccess access,boolean fire,WeaponFiringControl wfc,FireControlAccess original,ComputerFireControl.State state,BallisticComputerBlockEntity computer,ServerLevel level,long tick){
        if(!fire||wfc.cannonMount==null){access.fc$fire(false);return;}
        var actor=CombatServices.actor(level,wfc.cannonMount.getBlockPos());var entity=wfc.cannonMount.getContraption();if(actor.owner()==null||entity==null){access.fc$fire(false);return;}
        var load=CannonProbe.loadout(level,entity);var aim=CannonProbe.aim(level,entity);var shell=load.shell();
        // Unknown explosive clearance is refused rather than guessed.
        boolean kinetic=SafetyAmmunition.kinetic(entity);
        if(!kinetic||shell.muzzleSpeed()<=0||shell.lifetimeTicks()<=0||shell.lifetimeTicks()>1200){access.fc$fire(false);return;}
        var own=Ships.containing(level,wfc.cannonMount.getBlockPos());var vel=own==null?V3.ZERO:Ships.v3(Ships.velocityAt(level,own,Ships.vec(aim.pivot())));
        // The path follows the actual barrel, not a stale desired angle. The finite lifetime bounds its length.
        var solver=new FiringSolver(shell,aim.pivot(),vel,TargetMotion.fixed(aim.pivot()),0,shell.lifetimeTicks());
        var path=solver.path(aim.barrel(),shell.lifetimeTicks());
        if (!TrajectorySafety.clear(level,actor,path.stream().map(Ships::vec).toList(),0.5)) { access.fc$fire(false); return; }
        FireAuthorization.run(wfc, () -> access.fc$fire(true));
    }
}
