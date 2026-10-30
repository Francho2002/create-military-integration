package local.cmi.mixin.missiles;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import local.cmi.missiles.RadarSpearBridge;
import local.cmi.missiles.RadarSpearMode;

@Mixin(targets = "com.wariumce.procedures.RadarSpearFlightProcedure", remap = false)
public abstract class RadarSpearFlightMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cmi$managedFlight(LevelAccessor level, double x, double y, double z, Entity missile, CallbackInfo ci) {
        if (missile != null && RadarSpearMode.managed(missile.getPersistentData())) {
            ci.cancel();
            if (level instanceof ServerLevel server) RadarSpearBridge.tick(server, missile);
        }
    }
}
