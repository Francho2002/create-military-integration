package local.cmi.mixin.safety;
import local.cmi.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.munitions.*;
/** Extend collisions only when both bodies are supported, identified hostile military projectiles. */
@Mixin(value=AbstractCannonProjectile.class,remap=false)
public abstract class CannonInterceptionMixin {
    @Inject(method="canHitEntity",at=@At("HEAD"),cancellable=true,remap=false)
    private void cmi$canHit(Entity other,CallbackInfoReturnable<Boolean> cir){
        var self=(AbstractCannonProjectile)(Object)this;if(!(self.level() instanceof ServerLevel l)||!CombatServices.projectile(other))return;
        var c=CombatServices.resolve(l,new TargetId(TargetId.Kind.ENTITY,other.getUUID()));
        cir.setReturnValue(other!=self&&self.getOwner()!=other&&c.isPresent()&&CombatServices.canEngage(l,CombatServices.actor(l,self),c.get()));
    }
    @Inject(method="onHitEntity",at=@At("HEAD"),cancellable=true,remap=false)
    private void cmi$hit(Entity other,ProjectileContext context,CallbackInfoReturnable<Boolean> cir){
        var self=(AbstractCannonProjectile)(Object)this;if(!(self.level() instanceof ServerLevel l)||!CombatServices.projectile(other))return;
        var c=CombatServices.resolve(l,new TargetId(TargetId.Kind.ENTITY,other.getUUID()));
        if(c.isPresent()&&CombatServices.neutralize(l,CombatServices.actor(l,self),c.get())){self.discard();cir.setReturnValue(true);}else cir.setReturnValue(false);
    }
}
