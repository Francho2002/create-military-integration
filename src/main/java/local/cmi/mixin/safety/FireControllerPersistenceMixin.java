package local.cmi.mixin.safety;
import com.happysg.radar.block.controller.firing.FireControllerBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Skip the erroneous read in write. Preserve parent's serialization with an inherited super bridge. */
@Mixin(value=FireControllerBlockEntity.class,remap=false)
public abstract class FireControllerPersistenceMixin extends SmartBlockEntity {
    protected FireControllerPersistenceMixin(net.minecraft.world.level.block.entity.BlockEntityType<?> t,net.minecraft.core.BlockPos p,net.minecraft.world.level.block.state.BlockState s){super(t,p,s);}
    @Redirect(method="write",at=@At(value="INVOKE",target="Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V"),remap=false)
    private void cmi$writeParent(SmartBlockEntity receiver,CompoundTag tag,HolderLookup.Provider registries,boolean packet){super.write(tag,registries,packet);}
}
