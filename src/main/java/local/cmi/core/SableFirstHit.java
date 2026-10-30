package local.cmi.core;

import java.util.UUID;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

/** A fresh, bounded, conservative first-solid query. A result never authorizes damage. */
public final class SableFirstHit {
    public enum Kind { CLEAR, TARGET, OWN, FRIEND, OTHER_ENEMY, NEUTRAL, WORLD, UNKNOWN }
    /** worldHit is world-space; plotHit, plotPos and face are ship-plot-space. */
    public record Result(Kind kind, UUID shipId, Vec3 worldHit, Vec3 plotHit, BlockPos plotPos, Direction face, BlockState state, long queryTick, dev.ryanhcode.sable.companion.math.Pose3d pose) {
        public Result(Kind kind, UUID shipId, Vec3 worldHit, BlockPos plotPos, Direction face, BlockState state) {
            this(kind, shipId, worldHit, null, plotPos, face, state, -1, null);
        }
    }
    private static Result simple(Kind kind) { return new Result(kind, null, null, null, null, null, null, -1, null); }
    private SableFirstHit() {}

    public static Result evaluate(ServerLevel level, Actor actor, TargetId target, Vec3 from, Vec3 to, Entity ignored) {
        if (level == null || actor == null || actor.owner() == null || target == null || target.kind() != TargetId.Kind.SABLE || target.uuid() == null || !worldFinite(from) || !worldFinite(to) || from.distanceToSqr(to) < 1e-12 || from.distanceToSqr(to) > 4096) return simple(Kind.UNKNOWN);
        try {
            long queryTick = level.getGameTime();
            var container = SubLevelContainer.getContainer(level);
            if (container == null) return simple(Kind.UNKNOWN);
            AABB region = new AABB(from, to).inflate(1e-6);
            int x0 = (int)Math.floor(region.minX) >> 4, x1 = (int)Math.floor(region.maxX) >> 4;
            int z0 = (int)Math.floor(region.minZ) >> 4, z1 = (int)Math.floor(region.maxZ) >> 4;
            for (int x=x0; x<=x1; x++) for (int z=z0; z<=z1; z++) {
                Vec3 p = new Vec3(x*16.0+8, from.y, z*16.0+8);
                if (!territoryClear(level, actor, p)) return simple(Kind.UNKNOWN);
            }
            double entityDistance = Double.POSITIVE_INFINITY;
            Kind entityKind = Kind.UNKNOWN;
            // Entity boxes are conservative collision bounds; never skip an intersecting entity.
            for (Entity entity : level.getEntities((Entity)null, region)) {
                if (entity == ignored || !entity.isAlive()) continue;
                var box = entity.getBoundingBox();
                double distance = Double.POSITIVE_INFINITY;
                if (box.contains(from)) distance = 0;
                else { var hit = box.clip(from, to); if (hit.isPresent()) distance = from.distanceToSqr(hit.get()); }
                if (!Double.isFinite(distance)) continue;
                if (sameDistance(distance, entityDistance)) { entityKind = Kind.UNKNOWN; continue; }
                if (distance < entityDistance) {
                    entityDistance = distance;
                    var contact = CombatServices.resolve(level, new TargetId(TargetId.Kind.ENTITY, entity.getUUID()));
                    if (contact.isEmpty()) entityKind = Kind.UNKNOWN;
                    else if (actor.platform() != null && actor.platform().equals(contact.get().platform())) entityKind = Kind.OWN;
                    else entityKind = switch (CombatServices.relation(level, actor, contact.get())) {
                        case FRIEND -> Kind.FRIEND;
                        case ENEMY -> Kind.OTHER_ENEMY;
                        case NEUTRAL -> Kind.NEUTRAL;
                        default -> Kind.UNKNOWN;
                    };
                }
            }
            ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
            if (!(context instanceof ClipContextExtension extension)) return simple(Kind.UNKNOWN);
            extension.sable$setDoNotProject(true);
            var world = level.clip(context);
            double best = Double.POSITIVE_INFINITY;
            Result result = simple(Kind.CLEAR);
            if (world.getType() != HitResult.Type.MISS) {
                best = from.distanceToSqr(world.getLocation());
                result = new Result(Kind.WORLD, null, world.getLocation(), null, null, world.getDirection(), null, -1, null);
            }
            var query = new BoundingBox3d(region.minX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ);
            int budget = 12000;
            for (var ship : container.queryIntersecting(query)) {
                if (ship.isRemoved()) continue;
                if (ship.getLevel() != level || ship.getPlot() == null || ship.logicalPose() == null) return simple(Kind.UNKNOWN);
                var pose = new dev.ryanhcode.sable.companion.math.Pose3d(ship.logicalPose());
                Vec3 a = pose.transformPositionInverse(from), b = pose.transformPositionInverse(to);
                if (!finite(a) || !finite(b)) return simple(Kind.UNKNOWN);
                int divisions = PlotGeometryBounds.subdivisions(a.x,a.y,a.z,b.x,b.y,b.z);
                for (int i=0; i<divisions; i++) {
                    Vec3 start=a.lerp(b,(double)i/divisions), end=a.lerp(b,(double)(i+1)/divisions);
                    var bounds=PlotGeometryBounds.around(start.x,start.y,start.z,end.x,end.y,end.z,0);
                    for (int x=bounds.x0();x<=bounds.x1();x++) for(int y=bounds.y0();y<=bounds.y1();y++) for(int z=bounds.z0();z<=bounds.z1();z++) {
                        if (--budget < 0) return simple(Kind.UNKNOWN);
                        BlockPos pos=new BlockPos(x,y,z); ChunkPos chunkPos=new ChunkPos(pos);
                        if (!ship.getPlot().contains(chunkPos)) continue;
                        var chunk=ship.getPlot().getChunk(ship.getPlot().toLocal(chunkPos));
                        if (chunk==null || !level.hasChunkAt(pos)) return simple(Kind.UNKNOWN);
                        BlockState state=chunk.getBlockState(pos);
                        var shape=state.getCollisionShape(level,pos);
                        for (var box : shape.toAabbs()) if (box.move(pos).contains(a)) return simple(Kind.UNKNOWN);
                        var hit=shape.clip(a,b,pos);
                        if (hit==null) continue;
                        Vec3 point=pose.transformPosition(hit.getLocation());
                        if (!worldFinite(point)) return simple(Kind.UNKNOWN);
                        double distance=from.distanceToSqr(point);
                        if (sameDistance(distance, best) && (result.shipId()==null || !result.shipId().equals(ship.getUniqueId()) || !pos.equals(result.plotPos()))) return simple(Kind.UNKNOWN);
                        if (distance < best) {
                            var contact=CombatServices.resolve(level,new TargetId(TargetId.Kind.SABLE,ship.getUniqueId()));
                            if (contact.isEmpty()) return simple(Kind.UNKNOWN);
                            Relation relation=CombatServices.relation(level,actor,contact.get());
                            Kind kind = actor.platform() != null && actor.platform().equals(ship.getUniqueId()) ? Kind.OWN
                                    : relation == Relation.FRIEND ? Kind.FRIEND : relation == Relation.NEUTRAL ? Kind.NEUTRAL
                                    : relation != Relation.ENEMY ? Kind.UNKNOWN
                                    : ship.getUniqueId().equals(target.uuid()) && CombatServices.canEngage(level,actor,contact.get()) ? Kind.TARGET : Kind.OTHER_ENEMY;
                            if (!territoryClear(level,actor,point)) kind=Kind.UNKNOWN;
                            best=distance; result=new Result(kind,ship.getUniqueId(),point,hit.getLocation(),pos,hit.getDirection(),state,queryTick,pose);
                        }
                    }
                }
            }
            if (sameDistance(entityDistance, best)) return simple(Kind.UNKNOWN);
            if (entityDistance < best) return simple(entityKind);
            return result;
        } catch (RuntimeException unavailable) { return simple(Kind.UNKNOWN); }
    }
    /** Check the actual swept corridor up to, but not through, the reported hull. */
    public static boolean corridorClear(ServerLevel level, Actor actor, Vec3 from, Result result, Entity missile) {
        if (level == null || actor == null || missile == null || result == null || result.kind() != Kind.TARGET
                || !worldFinite(from) || !worldFinite(result.worldHit())) return false;
        double radius = missile.getBbWidth() * 0.5;
        Vec3 delta = result.worldHit().subtract(from);
        double length = delta.length();
        if (!Double.isFinite(radius) || radius <= 0 || radius > 64 || !Double.isFinite(length) || length <= 1e-5) return false;
        Vec3 end = result.worldHit().subtract(delta.scale(1e-5 / length));
        return TrajectorySafety.clear(level, actor, java.util.List.of(from, end), radius, missile)
                && mainWorldCorridorClear(level, actor, from, end, radius);
    }
    /** Account for the projectile radius against ordinary world collision shapes and protected positions. */
    public static boolean mainWorldCorridorClear(ServerLevel level, Actor actor, Vec3 from, Vec3 to, double radius) {
        if (level == null || actor == null || actor.owner() == null || !worldFinite(from) || !worldFinite(to)
                || !Double.isFinite(radius) || radius <= 0 || radius > 64) return false;
        try {
            int divisions = PlotGeometryBounds.subdivisions(from.x, from.y, from.z, to.x, to.y, to.z), budget = 12000;
            for (int i = 0; i < divisions; i++) {
                Vec3 a = from.lerp(to, (double)i / divisions), b = from.lerp(to, (double)(i + 1) / divisions);
                var bounds = PlotGeometryBounds.around(a.x, a.y, a.z, b.x, b.y, b.z, radius);
                for (int x = bounds.x0(); x <= bounds.x1(); x++) for (int y = bounds.y0(); y <= bounds.y1(); y++) for (int z = bounds.z0(); z <= bounds.z1(); z++) {
                    if (--budget < 0) return false;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!GeometryIntersection.intersects(new AABB(pos).inflate(radius), a, b)) continue;
                    if (!territoryClear(level, actor, Vec3.atCenterOf(pos))) return false;
                    var shape = level.getBlockState(pos).getCollisionShape(level, pos);
                    for (var box : shape.toAabbs()) if (GeometryIntersection.intersects(box.move(pos).inflate(radius), a, b)) return false;
                }
            }
            return true;
        } catch (RuntimeException unavailable) { return false; }
    }
    /** Do not permit structural fire through any FTB claim; manager absence is unknown. */
    public static boolean territoryClear(ServerLevel level, Actor actor, Vec3 point) {
        if (level == null || actor == null || actor.owner() == null || !worldFinite(point)) return false;
        try {
            if (CombatServices.isProtected(level, actor, point)) return false;
            if (!net.neoforged.fml.ModList.get().isLoaded("ftbchunks")) return false;
            var api = dev.ftb.mods.ftbchunks.api.FTBChunksAPI.api();
            if (!api.isManagerLoaded()) return false;
            var chunk = new net.minecraft.world.level.ChunkPos(BlockPos.containing(point));
            return api.getManager().getChunk(new dev.ftb.mods.ftblibrary.math.ChunkDimPos(level.dimension(), chunk)) == null;
        } catch (RuntimeException unavailable) { return false; }
    }
    // A fixed spatial tolerance in blocks, independent of the range of the hit.
    private static boolean sameDistance(double a, double b) {
        return Double.isFinite(a) && Double.isFinite(b) && Math.abs(Math.sqrt(a) - Math.sqrt(b)) <= 1e-5;
    }
    private static boolean finite(Vec3 v) { return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    private static boolean worldFinite(Vec3 v) { return finite(v) && Math.abs(v.x)<30000000 && Math.abs(v.y)<30000000 && Math.abs(v.z)<30000000; }
}
