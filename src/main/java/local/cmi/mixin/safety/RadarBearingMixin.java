package local.cmi.mixin.safety;
import com.happysg.radar.block.radar.bearing.RadarBearingBlockEntity;
import com.mrvew.firecontrol.ship.Ships;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Corrects the rotating scanner's azimuth only; stationary scanner already transforms its direction. */
@Mixin(value=RadarBearingBlockEntity.class,remap=false)
public abstract class RadarBearingMixin {
    @Shadow private Direction receiverFacing;
    @Inject(method="getGlobalAngle",at=@At("RETURN"),cancellable=true,remap=false)
    private void cmi$global(CallbackInfoReturnable<Float> cir){
        var be=(BlockEntity)(Object)this;if(be.getLevel()==null)return;var ship=Ships.containing(be.getLevel(),be.getBlockPos());if(ship==null)return;
        double radians=Math.toRadians(cir.getReturnValue());var direction=ship.logicalPose().transformNormal(new Vec3(Math.sin(radians),0,Math.cos(radians)));
        cir.setReturnValue((float)((Math.toDegrees(Math.atan2(direction.x,direction.z))+360)%360));
    }
}
