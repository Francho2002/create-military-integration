package local.cmi.mixin.safety;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import riftyboi.cbcmodernwarfare.cannon_control.compact_mount.CompactCannonMountBlockEntity;
@Mixin(value=CompactCannonMountBlockEntity.class,remap=false)
public abstract class CompactTickMixin {
    @Inject(method="tick",at=@At("TAIL"),remap=false)
    private void cmi$tick(CallbackInfo ci){var mount=(CompactCannonMountBlockEntity)(Object)this;if(mount.getLevel() instanceof ServerLevel l&&mount.getPersistentData().hasUUID("cmiOwner"))local.cmi.weapons.CompactCoordinator.tick(l,mount);}
}
