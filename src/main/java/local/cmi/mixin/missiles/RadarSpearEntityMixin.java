package local.cmi.mixin.missiles;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import local.cmi.missiles.RadarSpearBridge;
import local.cmi.missiles.RadarSpearMode;

@Mixin(targets = "com.wariumce.entity.RadarSpearMissileProjectileEntity", remap = false)
public abstract class RadarSpearEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cmi$identifyLaunch(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level() instanceof ServerLevel server) {
            RadarSpearBridge.initialize(server, self);
            if (!self.isRemoved() && RadarSpearMode.managed(self.getPersistentData()) && !RadarSpearBridge.preflight(server, self)) {
                local.cmi.missiles.RadarSpearDiagnostics.event(self, "preflight_rejected", "reason=unsafe_or_unresolved_path");
                RadarSpearBridge.discard(self);
            }
            if (self.isRemoved()) ci.cancel();
        }
    }
    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
    private void cmi$avoidNativeEntityExplosion(EntityHitResult hit, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level() instanceof ServerLevel server && RadarSpearMode.managed(self.getPersistentData())) {
            local.cmi.missiles.RadarSpearDiagnostics.event(self, "on_hit_entity", "entity=" + hit.getEntity().getUUID().toString().substring(0,8));
            ci.cancel();
            RadarSpearBridge.impactEntity(server, self, hit.getEntity());
        }
    }
    @Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true)
    private void cmi$avoidNativeBlockExplosion(BlockHitResult hit, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level() instanceof ServerLevel server && RadarSpearMode.managed(self.getPersistentData())) { ci.cancel(); RadarSpearBridge.impactBlock(server, self, hit); }
    }
}
