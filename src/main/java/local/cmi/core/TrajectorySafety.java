package local.cmi.core;

import java.util.List;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Fresh conservative corridor check; indeterminate geometry fails closed. */
public final class TrajectorySafety {
    private TrajectorySafety() {}

    public static boolean clear(ServerLevel level, Actor actor, List<Vec3> path, double blastRadius) {
        return clear(level,actor,path,blastRadius,null);
    }
    public static boolean clear(ServerLevel level, Actor actor, List<Vec3> path, double blastRadius, Entity ignored) {
        if (actor.owner() == null || !Double.isFinite(blastRadius) || blastRadius < 0 || blastRadius > 64 || path.size() < 2 || path.size() > 6001) return false;
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        int[] budget = {12000};
        for (int i = 1; i < path.size(); i++) {
            Vec3 from = path.get(i - 1), to = path.get(i);
            if (!finite(from) || !finite(to)) return false;
            AABB corridor = new AABB(from, to).inflate(blastRadius + 0.5);
            if (!entitiesClear(level, actor, corridor,ignored)) return false;
            // Check every intersected world chunk, including corners of the corridor.
            int minX = (int)Math.floor(corridor.minX) >> 4, maxX = (int)Math.floor(corridor.maxX) >> 4;
            int minZ = (int)Math.floor(corridor.minZ) >> 4, maxZ = (int)Math.floor(corridor.maxZ) >> 4;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                if (--budget[0] < 0) return false;
                Vec3 p = new Vec3(x * 16.0 + 8, from.y, z * 16.0 + 8);
                if (!level.hasChunkAt(BlockPos.containing(p)) || CombatServices.isProtected(level, actor, p)) return false;
            }
            if (level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType() != HitResult.Type.MISS) return false;
            AABB box = new AABB(from, to).inflate(blastRadius + 0.5 + 1.0e-6);
            var query = new BoundingBox3d(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            for (var ship : container.queryIntersecting(query)) {
                if (ship.isRemoved()) continue;
                if (ship.getLevel() != level) return false;
                var plot = ship.getPlot();
                if (plot == null) return false;
                if (ship.logicalPose() == null) return false;
                Vec3 a = ship.logicalPose().transformPositionInverse(from);
                Vec3 b = ship.logicalPose().transformPositionInverse(to);
                if (!finite(a) || !finite(b)) return false;
                int divisions;
                try { divisions = PlotGeometryBounds.subdivisions(a.x, a.y, a.z, b.x, b.y, b.z); }
                catch (IllegalArgumentException ex) { return false; }
                var contact = CombatServices.resolve(level, new TargetId(TargetId.Kind.SABLE, ship.getUniqueId()));
                boolean enemy = contact.isPresent() && CombatServices.canEngage(level, actor, contact.get());
                for (int segment = 0; segment < divisions; segment++) {
                  Vec3 start = a.lerp(b, (double)segment / divisions);
                  Vec3 end = a.lerp(b, (double)(segment + 1) / divisions);
                  PlotGeometryBounds bounds;
                  try { bounds = PlotGeometryBounds.around(start.x, start.y, start.z, end.x, end.y, end.z, blastRadius); }
                  catch (IllegalArgumentException ex) { return false; }
                  for (int x = bounds.x0(); x <= bounds.x1(); x++) for (int y = bounds.y0(); y <= bounds.y1(); y++) for (int z = bounds.z0(); z <= bounds.z1(); z++) {
                    if (--budget[0] < 0) return false;
                    BlockPos pos = new BlockPos(x, y, z);
                    var chunkPos = new net.minecraft.world.level.ChunkPos(pos);
                    // Plot coordinates occupy reserved chunks of the parent ServerLevel; the
                    // plot holder is authoritative and may be absent while unloading.
                    if (!plot.contains(chunkPos)) continue;
                    try {
                        var chunk = plot.getChunk(plot.toLocal(chunkPos));
                        if (chunk == null || !level.hasChunkAt(pos)) return false;
                        var shape = chunk.getBlockState(pos).getCollisionShape(ship.getLevel(), pos);
                        for (var shapeBox : shape.toAabbs()) {
                            boolean intersects = GeometryIntersection.intersects(shapeBox.move(pos).inflate(blastRadius + 0.5), a, b);
                            if (PlotGeometryBounds.blocks(enemy, intersects)) return false;
                        }
                    } catch (RuntimeException unavailableGeometry) {
                        return false;
                    }
                  }
                }
                // Enemy solids may be penetrated; always inspect the rest of the path.
            }
        }
        return true;
    }

    private static boolean entitiesClear(ServerLevel level, Actor actor, AABB bounds,Entity ignored) {
        for (Entity entity : level.getEntities((Entity)null, bounds)) {
            if (entity==ignored||!entity.isAlive()) continue;
            var contact = CombatServices.resolve(level, new TargetId(TargetId.Kind.ENTITY, entity.getUUID()));
            if (contact.isEmpty() || CombatServices.relation(level, actor, contact.get()) != Relation.ENEMY) return false;
        }
        return true;
    }

    private static boolean finite(Vec3 p) { return Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z); }
}
