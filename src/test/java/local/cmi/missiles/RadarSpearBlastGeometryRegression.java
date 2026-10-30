package local.cmi.missiles;

import java.util.function.UnaryOperator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Pure coordinate checks; no Minecraft world, Sable runtime, or GameTest is started. */
public final class RadarSpearBlastGeometryRegression {
    public static void main(String[] args) {
        // A 90-degree yaw plus translation and 2x scale verifies that the effect radius remains world-space.
        UnaryOperator<Vec3> toWorld=p->new Vec3(100-2*p.z,50+2*p.y,-30+2*p.x);
        UnaryOperator<Vec3> toPlot=p->new Vec3((p.z+30)/2,(p.y-50)/2,(100-p.x)/2);
        Vec3 centerPlot=new Vec3(15.5,64.5,15.5),centerWorld=toWorld.apply(centerPlot);
        AABB bounds=RadarSpearBlastGeometry.worldSphereBoundsInPlot(centerWorld,3.0,toPlot);
        check(bounds!=null && bounds.minX<15 && bounds.maxX>16 && bounds.minZ<15 && bounds.maxZ>16);
        // The inverse-transformed world cube is an enumeration superset; actual inclusion is tested in world units.
        for (int x=-3;x<=3;x++) for (int y=-3;y<=3;y++) for (int z=-3;z<=3;z++) {
            Vec3 sample=centerWorld.add(x*0.5,y*0.5,z*0.5);
            if (centerWorld.distanceToSqr(sample)<=9.0) {
                Vec3 plotSample=toPlot.apply(sample);
                check(contains(bounds,plotSample));
            }
        }
        AABB block15=new AABB(15,64,15,16,65,16),block16=new AABB(16,64,15,17,65,16);
        check(RadarSpearBlastGeometry.intersectsWorldSphere(centerWorld,block15,toPlot,toWorld,3.0));
        check(RadarSpearBlastGeometry.intersectsWorldSphere(centerWorld,block16,toPlot,toWorld,3.0));
        // Block coordinates 15 -> 16 cross a chunk boundary on X; Z remains in the same chunk.
        ChunkPos leftOfBoundary=new ChunkPos(new net.minecraft.core.BlockPos(15,64,15));
        ChunkPos rightOfBoundary=new ChunkPos(new net.minecraft.core.BlockPos(16,64,15));
        check(leftOfBoundary.x==0 && rightOfBoundary.x==1 && leftOfBoundary.z==rightOfBoundary.z);
        ChunkPos negativeEdge=new ChunkPos(new net.minecraft.core.BlockPos(-1,64,-1));
        check(negativeEdge.x==-1 && negativeEdge.z==-1);
        check(!RadarSpearBlastGeometry.intersectsWorldSphere(centerWorld,new AABB(20,64,15,21,65,16),toPlot,toWorld,3.0));
        // A 2x plot scale does not enlarge the 3-block world-space radius.
        AABB beyondWorldRadius=new AABB(18,64,15,19,65,16);
        check(centerWorld.distanceToSqr(toWorld.apply(new Vec3(18,64.5,15.5)))>9.0);
        check(!RadarSpearBlastGeometry.intersectsWorldSphere(centerWorld,beyondWorldRadius,toPlot,toWorld,3.0));
        check(!RadarSpearBlastGeometry.intersectsWorldSphere(centerWorld,block15,toPlot,toWorld,Double.NaN));
        check(RadarSpearBlastGeometry.worldSphereBoundsInPlot(centerWorld,Double.POSITIVE_INFINITY,toPlot)==null);

        // Deliberately different axis extents catch X/Y/Z substitutions such as z1-y0.
        check(RadarSpearBlastGeometry.inclusiveCellCount(10,12,60,64,200,207)==120L);
        check(RadarSpearBlastGeometry.inclusiveCellCount(-1,1,-2,2,-3,3)==105L);
        check(RadarSpearBlastGeometry.inclusiveCellCount(-5,-3,-10,-9,-20,-17)==24L);
        check(RadarSpearBlastGeometry.inclusiveCellCount(4,4,-2,-2,9,9)==1L);
        check(RadarSpearBlastGeometry.inclusiveCellCount(0,0,0,0,0,4094)==4095L);
        check(RadarSpearBlastGeometry.inclusiveCellCount(0,0,0,0,0,4095)==RadarSpearAreaDamage.MAX_CANDIDATE_CELLS);
        check(RadarSpearBlastGeometry.inclusiveCellCount(0,0,0,0,0,4096)==4097L);
        check(RadarSpearBlastGeometry.inclusiveCellCount(0,0,0,0,0,4094)<RadarSpearAreaDamage.MAX_CANDIDATE_CELLS);
        check(RadarSpearBlastGeometry.inclusiveCellCount(0,0,0,0,0,4096)>RadarSpearAreaDamage.MAX_CANDIDATE_CELLS);
        expect(IllegalArgumentException.class,()->RadarSpearBlastGeometry.inclusiveCellCount(2,1,0,0,0,0));
        expect(IllegalArgumentException.class,()->RadarSpearBlastGeometry.inclusiveCellCount(0,0,2,1,0,0));
        expect(IllegalArgumentException.class,()->RadarSpearBlastGeometry.inclusiveCellCount(0,0,0,0,2,1));
        expect(ArithmeticException.class,()->RadarSpearBlastGeometry.inclusiveCellCount(
                Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE));
        System.out.println("Radar Spear transformed blast geometry regression checks passed");
    }
    private static boolean contains(AABB box,Vec3 p) {
        return p.x>=box.minX&&p.x<=box.maxX&&p.y>=box.minY&&p.y<=box.maxY&&p.z>=box.minZ&&p.z<=box.maxZ;
    }
    private static void expect(Class<? extends RuntimeException> type,Runnable test) {
        try { test.run(); } catch (RuntimeException failure) {
            if(type.isInstance(failure)) return;
            throw new AssertionError("Expected "+type.getSimpleName()+" but got "+failure,failure);
        }
        throw new AssertionError("Expected "+type.getSimpleName());
    }
    private static void check(boolean value) { if(!value) throw new AssertionError("Radar Spear blast geometry regression"); }
}
