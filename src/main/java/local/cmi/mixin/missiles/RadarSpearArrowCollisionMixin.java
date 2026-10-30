package local.cmi.mixin.missiles;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wariumce.entity.RadarSpearMissileProjectileEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import local.cmi.missiles.RadarSpearMode;
import local.cmi.missiles.RadarSpearBridge;
import local.cmi.missiles.RadarSpearCollisionCoordinates;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Keep the real block result while entity tracing uses its world-space endpoint. */
@Mixin(AbstractArrow.class)
public abstract class RadarSpearArrowCollisionMixin {
    @Unique private static final String CMI_LOCATION = "Lnet/minecraft/world/phys/HitResult;getLocation()Lnet/minecraft/world/phys/Vec3;";

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = CMI_LOCATION), require = 1)
    private Vec3 cmi$worldEntityTraceEnd(HitResult result, Operation<Vec3> original) {
        Vec3 location = original.call(result);
        AbstractArrow self = (AbstractArrow) (Object) this;
        if (!cmi$managed(self)) return location;
        Vec3 world = cmi$worldEnd((ServerLevel) self.level(), result, location);
        if (world == null) { RadarSpearBridge.discard(self); return location; }
        return world;
    }

    @Unique private static boolean cmi$managed(AbstractArrow arrow) {
        return arrow instanceof RadarSpearMissileProjectileEntity && arrow.level() instanceof ServerLevel
                && RadarSpearMode.managed(arrow.getPersistentData());
    }

    // Null is an ambiguous plot, never a synthetic hit.
    @Unique private static Vec3 cmi$worldEnd(ServerLevel level, HitResult result, Vec3 location) {
        if (!(result instanceof BlockHitResult hit)) return null;
        // Another wrapper may already have transformed the returned location.
        // Its coordinate space is unknown: do not transform it a second time.
        if (!java.util.Objects.equals(location, hit.getLocation())) return null;
        try {
            var container = SubLevelContainer.getContainer(level);
            if (container == null) return null;
            if (!container.inBounds(hit.getBlockPos())) return RadarSpearCollisionCoordinates.worldEndpoint(location, hit.getBlockPos(), false, null);
            var chunk = new ChunkPos(hit.getBlockPos());
            var plot = container.getPlot(chunk);
            if (plot == null || !plot.contains(chunk) || plot.getChunk(plot.toLocal(chunk)) == null || !level.hasChunkAt(hit.getBlockPos())) return null;
            var ship = plot.getSubLevel();
            if (ship == null || ship.isRemoved() || ship.getLevel() != level || ship.logicalPose() == null) return null;
            var pose = new dev.ryanhcode.sable.companion.math.Pose3d(ship.logicalPose());
            return RadarSpearCollisionCoordinates.worldEndpoint(location, hit.getBlockPos(), true, pose);
        } catch (RuntimeException unavailable) { return null; }
    }
}
