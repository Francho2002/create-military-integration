package local.cmi.missiles;

import com.mrvew.firecontrol.target.Sensor;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import local.cmi.core.Actor;
import local.cmi.core.CombatServices;
import local.cmi.core.MilitaryContact;
import local.cmi.core.Relation;
import local.cmi.core.SableFirstHit;
import local.cmi.core.TargetId;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.CommonHooks;

/**
 * CMI's bounded structural-area adaptation for an explicitly authorized Radar Spear.
 * It never calls Warium's native explosion, fragment, ERA, or deferred-work procedures.
 */
public final class RadarSpearAreaDamage {
    /** Warium SmallExplosionProcedure calls Level.explode with strength 3.0/TNT. CMI uses this as a conservative spatial envelope, not as a vanilla explosion simulation. */
    public static final double STRUCTURAL_ENVELOPE = 3.0;
    static final int MAX_CANDIDATE_CELLS = 4096;
    private static final int MAX_GEOMETRY_CHECKS = 65_536;
    private static final int MIN_BLOCKS_PER_EFFECT = 2;
    private RadarSpearAreaDamage() {}

    private record Change(BlockPos pos, BlockState before, BlockState after, boolean destroy) {}

    public static boolean impact(ServerLevel level, Entity missile, BlockHitResult hit, Vec3 from, Vec3 to) {
        try { return impactChecked(level,missile,hit,from,to); }
        catch (RuntimeException unavailable) { return false; }
    }

    private static boolean impactChecked(ServerLevel level, Entity missile, BlockHitResult hit, Vec3 from, Vec3 to) {
        if (level == null || missile == null || missile.level() != level || hit == null || !missile.isAlive()
                || !level.getServer().isSameThread() || !RadarSpearMode.managed(missile.getPersistentData())) return false;
        var data=missile.getPersistentData();
        if (!data.hasUUID("cmiOwner") || !data.hasUUID("cmiTargetId") || !"SABLE".equals(data.getString("cmiTargetKind"))
                || !data.contains("Time",net.minecraft.nbt.Tag.TAG_DOUBLE) || !Double.isFinite(data.getDouble("Time")) || data.getDouble("Time")<10) return false;
        Actor actor=new Actor(data.getUUID("cmiOwner"),data.getString("cmiIff"),data.hasUUID("cmiPlatform")?data.getUUID("cmiPlatform"):null);
        TargetId target=new TargetId(TargetId.Kind.SABLE,data.getUUID("cmiTargetId"));
        long tick=level.getGameTime();
        if (!RadarSpearImpact.matchesProfile(data,missile.getUUID(),tick,actor,target,"explosive_sable")) return false;
        if (!(level.getPlayerByUUID(actor.owner()) instanceof ServerPlayer owner) || !owner.isAlive() || owner.level()!=level) return false;
        var container=SubLevelContainer.getContainer(level);
        SubLevel ship=container==null?null:container.getSubLevel(target.uuid());
        if (ship==null || ship.isRemoved() || ship.getLevel()!=level || ship.logicalPose()==null || ship.getPlot()==null) return false;
        Pose3d pose=new Pose3d(ship.logicalPose());
        var first=SableFirstHit.evaluate(level,actor,target,from,to,missile);
        Vec3 worldCenter=first==null?null:first.worldHit();
        if (!RadarSpearStructural.queryMatches(first,tick,pose,ship.logicalPose())
                || !RadarSpearStructural.confirmed(first,target,hit,pose.transformPosition(hit.getLocation()))
                || first.state()==null || !SableFirstHit.corridorClear(level,actor,from,first,missile)
                || !SableFirstHit.territoryClear(level,actor,worldCenter)
                || CombatServices.resolve(level,target).filter(c->CombatServices.canEngage(level,actor,c)).isEmpty()
                || !enemyCrewOnly(level,actor,target.uuid(),worldCenter)) return false;

        Vec3 plotCenter=pose.transformPositionInverse(worldCenter);
        if (!RadarSpearBlastGeometry.finite(plotCenter) || plotCenter.distanceToSqr(first.plotHit())>1.0e-10) return false;
        var inverse=(java.util.function.UnaryOperator<Vec3>)pose::transformPositionInverse;
        var forward=(java.util.function.UnaryOperator<Vec3>)pose::transformPosition;
        AABB bounds=RadarSpearBlastGeometry.worldSphereBoundsInPlot(worldCenter,STRUCTURAL_ENVELOPE,inverse);
        if (bounds==null || !bounded(bounds)) return false;
        int x0=floor(bounds.minX),y0=floor(bounds.minY),z0=floor(bounds.minZ);
        int x1=floor(bounds.maxX),y1=floor(bounds.maxY),z1=floor(bounds.maxZ);
        final long cells;
        try {
            cells=RadarSpearBlastGeometry.inclusiveCellCount(x0,x1,y0,y1,z0,z1);
        } catch (IllegalArgumentException | ArithmeticException invalidBounds) {
            return false;
        }
        if (cells>MAX_CANDIDATE_CELLS) return false;

        LevelPlot plot=ship.getPlot();
        PlotView view=new PlotView(level,plot);
        List<Change> changes=new ArrayList<>();
        int[] checks={0};
        for (int x=x0;x<=x1;x++) for (int y=y0;y<=y1;y++) for (int z=z0;z<=z1;z++) {
            BlockPos pos=new BlockPos(x,y,z);
            BlockState state=view.state(pos);
            if (state==null) return false;
            Change change=changeFor(pos,state);
            if (change==null || state.hasBlockEntity() || view.blockEntity(pos)!=null) continue;
            if (view.unknown || !view.neighborhoodKnown(pos)) return false;
            VoxelShape shape=state.getCollisionShape(view.plotLevel,pos);
            boolean inEnvelope=false,visible=false;
            for (AABB localBox:shape.toAabbs()) {
                AABB plotBox=localBox.move(pos);
                if (RadarSpearBlastGeometry.intersectsWorldSphere(worldCenter,plotBox,inverse,forward,STRUCTURAL_ENVELOPE)) {
                    inEnvelope=true;
                    Vec3 point=closest(plotCenter,plotBox);
                    if (pos.equals(first.plotPos()) || exposed(view,plotCenter,point,first.plotPos(),pos,checks)) { visible=true;break; }
                }
            }
            if (checks[0]>MAX_GEOMETRY_CHECKS || view.unknown) return false;
            if (!inEnvelope || !visible) continue;
            Vec3 blockWorld=forward.apply(Vec3.atCenterOf(pos));
            if (!safeTerritory(level,actor,forward,pos) || CombatServices.isProtected(level,actor,blockWorld)) continue;
            changes.add(change);
        }
        if (view.unknown || changes.size()<MIN_BLOCKS_PER_EFFECT || changes.size()>MAX_CANDIDATE_CELLS) return false;

        // Consume before external block listeners; re-entrant or repeated callbacks cannot apply the area twice.
        if (!RadarSpearImpact.consume(data,missile.getUUID(),level.getGameTime())) return false;
        var frozen=data.copy();
        List<Change> approved=new ArrayList<>();
        for (Change change:changes) {
            if (!stillAuthorized(level,missile,owner,actor,target,container,ship,pose,tick,frozen,view,change,worldCenter,plotCenter,first.plotPos(),inverse,forward,checks)) return false;
            if (CommonHooks.fireBlockBreak(level,owner.gameMode.getGameModeForPlayer(),owner,change.pos(),change.before()).isCanceled()) continue;
            if (!stillAuthorized(level,missile,owner,actor,target,container,ship,pose,tick,frozen,view,change,worldCenter,plotCenter,first.plotPos(),inverse,forward,checks)) return false;
            approved.add(change);
        }
        if (approved.size()<MIN_BLOCKS_PER_EFFECT
                || !impactStillConfirmed(level,actor,target,missile,from,to,hit,first,ship,pose,tick)) return false;
        int applied=0;
        for (Change change:approved) {
            if (!stillAuthorized(level,missile,owner,actor,target,container,ship,pose,tick,frozen,view,change,worldCenter,plotCenter,first.plotPos(),inverse,forward,checks)) break;
            boolean changed=change.destroy()?level.destroyBlock(change.pos(),false):level.setBlock(change.pos(),change.after(),3);
            if (changed) applied++;
        }
        return applied>0;
    }

    private static boolean enemyCrewOnly(ServerLevel level,Actor actor,UUID shipId,Vec3 worldCenter) {
        if (!RadarSpearBlastGeometry.finite(worldCenter)) return false;
        var crew=Sensor.crews(level).getOrDefault(shipId,List.of());
        for (ServerPlayer player:crew) {
            if (player==null || !player.isAlive() || player.level()!=level) return false;
            var contact=CombatServices.resolve(level,new TargetId(TargetId.Kind.ENTITY,player.getUUID()));
            if (contact.isEmpty() || CombatServices.relation(level,actor,contact.get())!=Relation.ENEMY) return false;
        }
        AABB nearby=new AABB(worldCenter,worldCenter).inflate(STRUCTURAL_ENVELOPE);
        for (Entity entity:level.getEntities(null,nearby)) {
            var carrying=Sable.HELPER.getTrackingOrVehicleSubLevel(entity);
            if (entity.isAlive() && carrying!=null && shipId.equals(carrying.getUniqueId())) {
                var contact=CombatServices.resolve(level,new TargetId(TargetId.Kind.ENTITY,entity.getUUID()));
                if (contact.isEmpty() || CombatServices.relation(level,actor,contact.get())!=Relation.ENEMY) return false;
            }
        }
        return true;
    }

    private static boolean stillAuthorized(ServerLevel level,Entity missile,ServerPlayer owner,Actor actor,TargetId target,
            SubLevelContainer container,SubLevel ship,Pose3d pose,long tick,net.minecraft.nbt.CompoundTag frozen,
            PlotView view,Change change,Vec3 worldCenter,Vec3 plotCenter,BlockPos sourcePlot,
            java.util.function.UnaryOperator<Vec3> inverse,java.util.function.UnaryOperator<Vec3> forward,int[] checks) {
        if (!missile.isAlive() || owner==null || !owner.isAlive() || owner.level()!=level || level.getPlayerByUUID(actor.owner())!=owner
                || level.getGameTime()!=tick || !frozen.equals(missile.getPersistentData()) || view.unknown
                || container.getSubLevel(target.uuid())!=ship || ship.isRemoved() || ship.getLevel()!=level || ship.logicalPose()==null
                || !RadarSpearStructural.samePose(pose,ship.logicalPose()) || ship.getPlot()!=view.plot
                || CombatServices.resolve(level,target).filter(c->CombatServices.canEngage(level,actor,c)).isEmpty()
                || !enemyCrewOnly(level,actor,target.uuid(),worldCenter)) return false;
        view.refresh();
        BlockState state=view.state(change.pos());
        if (state==null || !state.equals(change.before()) || state.hasBlockEntity() || view.blockEntity(change.pos())!=null
                || !view.neighborhoodKnown(change.pos()) || !safeTerritory(level,actor,forward,change.pos())
                || CombatServices.isProtected(level,actor,forward.apply(Vec3.atCenterOf(change.pos())))) return false;
        VoxelShape shape=state.getCollisionShape(view.plotLevel,change.pos());
        for (AABB localBox:shape.toAabbs()) {
            AABB plotBox=localBox.move(change.pos());
            if (!RadarSpearBlastGeometry.intersectsWorldSphere(worldCenter,plotBox,inverse,forward,STRUCTURAL_ENVELOPE)) continue;
            if (change.pos().equals(sourcePlot) || exposed(view,plotCenter,closest(plotCenter,plotBox),sourcePlot,change.pos(),checks)) return true;
        }
        return false;
    }

    private static boolean impactStillConfirmed(ServerLevel level,Actor actor,TargetId target,Entity missile,Vec3 from,Vec3 to,
            BlockHitResult hit,SableFirstHit.Result original,SubLevel ship,Pose3d pose,long tick) {
        if (level.getGameTime()!=tick || original==null || ship==null || ship.isRemoved() || ship.logicalPose()==null
                || !RadarSpearStructural.samePose(pose,ship.logicalPose())) return false;
        var again=SableFirstHit.evaluate(level,actor,target,from,to,missile);
        return RadarSpearStructural.queryMatches(again,tick,pose,ship.logicalPose())
                && RadarSpearStructural.confirmed(again,target,hit,pose.transformPosition(hit.getLocation()))
                && again.plotPos().equals(original.plotPos()) && again.face()==original.face()
                && again.state()!=null && again.state().equals(original.state())
                && SableFirstHit.corridorClear(level,actor,from,again,missile)
                && SableFirstHit.territoryClear(level,actor,again.worldHit())
                && CombatServices.resolve(level,target).filter(c->CombatServices.canEngage(level,actor,c)).isPresent()
                && enemyCrewOnly(level,actor,target.uuid(),again.worldHit());
    }

    private static boolean safeTerritory(ServerLevel level,Actor actor,java.util.function.UnaryOperator<Vec3> forward,BlockPos pos) {
        for (int x=0;x<=1;x++) for (int y=0;y<=1;y++) for (int z=0;z<=1;z++) {
            Vec3 point=forward.apply(new Vec3(pos.getX()+x,pos.getY()+y,pos.getZ()+z));
            if (!SableFirstHit.territoryClear(level,actor,point)) return false;
        }
        return true;
    }

    private static boolean exposed(PlotView view,Vec3 start,Vec3 end,BlockPos source,BlockPos destination,int[] checks) {
        Vec3 d=end.subtract(start);
        if (d.lengthSqr()<1.0e-14) return true;
        int x=floor(start.x),y=floor(start.y),z=floor(start.z);
        int sx=Double.compare(d.x,0),sy=Double.compare(d.y,0),sz=Double.compare(d.z,0);
        double tx=firstBoundary(start.x,d.x,x,sx),ty=firstBoundary(start.y,d.y,y,sy),tz=firstBoundary(start.z,d.z,z,sz);
        double dx=sx==0?Double.POSITIVE_INFINITY:Math.abs(1.0/d.x),dy=sy==0?Double.POSITIVE_INFINITY:Math.abs(1.0/d.y),dz=sz==0?Double.POSITIVE_INFINITY:Math.abs(1.0/d.z);
        Set<BlockPos> visited=new HashSet<>();
        double length2=d.lengthSqr();
        for (int step=0;step<96;step++) {
            for (int ox=-1;ox<=1;ox++) for (int oy=-1;oy<=1;oy++) for (int oz=-1;oz<=1;oz++) {
                BlockPos p=new BlockPos(x+ox,y+oy,z+oz);
                if (!visited.add(p)) continue;
                if (++checks[0]>MAX_GEOMETRY_CHECKS) return false;
                if (p.equals(destination)) continue;
                BlockState state=view.state(p);
                if (state==null) return false;
                if (!view.neighborhoodKnown(p)) return false;
                VoxelShape shape=state.getCollisionShape(view.plotLevel,p);
                if (shape.isEmpty()) continue;
                var hit=shape.clip(start,end,p);
                if (hit==null) continue;
                double t=hit.getLocation().subtract(start).dot(d)/length2;
                if (t>=1.0-1.0e-7) continue;
                if (p.equals(source) && t<=1.0e-7) {
                    Vec3 probe=start.add(d.scale(1.0e-5));
                    boolean enters=false;
                    for (AABB box:shape.toAabbs()) if (box.move(p).contains(probe)) { enters=true;break; }
                    if (!enters) continue;
                }
                return false;
            }
            double next=Math.min(tx,Math.min(ty,tz));
            if (!Double.isFinite(next) || next>=1.0) return true;
            if (tx<=next+1.0e-12) { x+=sx;tx+=dx; }
            if (ty<=next+1.0e-12) { y+=sy;ty+=dy; }
            if (tz<=next+1.0e-12) { z+=sz;tz+=dz; }
        }
        return false;
    }

    private static double firstBoundary(double origin,double delta,int voxel,int step) {
        if (step==0) return Double.POSITIVE_INFINITY;
        return ((step>0?voxel+1.0:voxel)-origin)/delta;
    }

    private static Change changeFor(BlockPos pos,BlockState state) {
        BlockState after=areaReplacement(state);
        if (after!=null) return new Change(pos,state,after,false);
        var block=state.getBlock();
        if (block==Blocks.OBSIDIAN || block==Blocks.CRYING_OBSIDIAN) return new Change(pos,state,null,true);
        return null;
    }

    /** Exact one-step mappings from HeavyCrackProcedureProcedure, except its unsafe ERA route. */
    static BlockState areaReplacement(BlockState state) {
        var b=state.getBlock();
        if (b==Blocks.DIORITE || b==com.wariumce.init.CrustyChunksModBlocks.DESTROYED_CONCRETE.get()) return Blocks.GRAVEL.defaultBlockState();
        if (b==com.wariumce.init.CrustyChunksModBlocks.DAMAGED_CONCRETE.get()) return com.wariumce.init.CrustyChunksModBlocks.DESTROYED_CONCRETE.get().defaultBlockState();
        if (b==com.wariumce.init.CrustyChunksModBlocks.FRACTURED_CONCRETE.get()) return com.wariumce.init.CrustyChunksModBlocks.DAMAGED_CONCRETE.get().defaultBlockState();
        if (b==com.wariumce.init.CrustyChunksModBlocks.CRACKED_CONCRETE.get()) return com.wariumce.init.CrustyChunksModBlocks.FRACTURED_CONCRETE.get().defaultBlockState();
        if (b==com.wariumce.init.CrustyChunksModBlocks.REENFORCED_CONCRETE.get()) return com.wariumce.init.CrustyChunksModBlocks.CRACKED_CONCRETE.get().defaultBlockState();
        if (b==com.wariumce.init.CrustyChunksModBlocks.DAMAGED_CONCRETE_WALL.get()) return com.wariumce.init.CrustyChunksModBlocks.DESTROYED_CONCRETE_WALL.get().defaultBlockState();
        if (b==com.wariumce.init.CrustyChunksModBlocks.FRACTURED_CONCRETE_WALL.get()) return com.wariumce.init.CrustyChunksModBlocks.DAMAGED_CONCRETE_WALL.get().defaultBlockState();
        if (b==com.wariumce.init.CrustyChunksModBlocks.CRACKED_CONCRETE_WALL.get()) return com.wariumce.init.CrustyChunksModBlocks.FRACTURED_CONCRETE_WALL.get().defaultBlockState();
        if (b==com.wariumce.init.CrustyChunksModBlocks.CONCRETE_WALL.get()) return com.wariumce.init.CrustyChunksModBlocks.CRACKED_CONCRETE_WALL.get().defaultBlockState();
        if (b==Blocks.GRANITE || b==Blocks.ANDESITE || b==Blocks.TUFF || b==Blocks.STONE) return Blocks.COBBLESTONE.defaultBlockState();
        if (b==Blocks.DEEPSLATE || b==Blocks.CRACKED_DEEPSLATE_BRICKS || b==Blocks.CRACKED_DEEPSLATE_TILES) return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        if (b==Blocks.GRASS_BLOCK) return Blocks.DIRT.defaultBlockState();
        if (b==Blocks.CRACKED_STONE_BRICKS) return Blocks.COBBLESTONE.defaultBlockState();
        if (b==Blocks.STONE_BRICKS || b==Blocks.MOSSY_STONE_BRICKS) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        if (b==Blocks.DEEPSLATE_BRICKS) return Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState();
        if (b==Blocks.DEEPSLATE_TILES) return Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState();
        if (b==Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS) return Blocks.BLACKSTONE.defaultBlockState();
        if (b==Blocks.POLISHED_BLACKSTONE_BRICKS) return Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        return null;
    }

    private static Vec3 closest(Vec3 center,AABB box) {
        return new Vec3(Math.max(box.minX,Math.min(box.maxX,center.x)),Math.max(box.minY,Math.min(box.maxY,center.y)),Math.max(box.minZ,Math.min(box.maxZ,center.z)));
    }
    private static boolean bounded(AABB b) {
        return finite(b.minX)&&finite(b.minY)&&finite(b.minZ)&&finite(b.maxX)&&finite(b.maxY)&&finite(b.maxZ)
                && Math.abs(b.minX)<30_000_000&&Math.abs(b.minY)<30_000_000&&Math.abs(b.minZ)<30_000_000
                && Math.abs(b.maxX)<30_000_000&&Math.abs(b.maxY)<30_000_000&&Math.abs(b.maxZ)<30_000_000;
    }
    private static int floor(double d) { return (int)Math.floor(d); }
    private static boolean finite(double d) { return Double.isFinite(d); }

    private static final class PlotView {
        final ServerLevel level;
        final LevelPlot plot;
        final EmbeddedPlotLevelAccessor plotLevel;
        final Map<ChunkPos,LevelChunk> chunks=new HashMap<>();
        final Map<BlockPos,BlockState> states=new HashMap<>();
        boolean unknown;
        PlotView(ServerLevel level,LevelPlot plot) { this.level=level;this.plot=plot;this.plotLevel=plot.getEmbeddedLevelAccessor(); }
        void refresh() { chunks.clear();states.clear();unknown=false; }
        BlockState state(BlockPos pos) {
            BlockState cached=states.get(pos);
            if (cached!=null) return cached;
            ChunkPos chunkPos=new ChunkPos(pos);
            if (!plot.contains(chunkPos)) return Blocks.AIR.defaultBlockState();
            LevelChunk chunk=chunks.get(chunkPos);
            if (chunk==null) {
                chunk=plot.getChunk(plot.toLocal(chunkPos));
                if (chunk==null) { unknown=true;return null; }
                chunks.put(chunkPos,chunk);
            }
            BlockState state=chunk.getBlockState(pos);
            states.put(pos,state);
            return state;
        }
        boolean neighborhoodKnown(BlockPos pos) {
            for (BlockPos neighbor:List.of(pos.north(),pos.south(),pos.east(),pos.west(),pos.above(),pos.below()))
                if (state(neighbor)==null) return false;
            return true;
        }
        net.minecraft.world.level.block.entity.BlockEntity blockEntity(BlockPos pos) {
            ChunkPos chunkPos=new ChunkPos(pos);
            if (!plot.contains(chunkPos)) return null;
            if (state(pos)==null) return null;
            LevelChunk chunk=chunks.get(chunkPos);
            return chunk==null?null:chunk.getBlockEntity(pos);
        }
    }
}
