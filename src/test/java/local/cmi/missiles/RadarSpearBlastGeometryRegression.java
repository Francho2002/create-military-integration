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
        AABB block15=new AABB(15,64,15,16,65,16),block16=new AABB(16,64,15,17,65,16);
        check(RadarSpearBlastGeometry.intersectsWorldSphere(centerWorld,block15,toPlot,toWorld,3.0));
        check(RadarSpearBlastGeometry.intersectsWorldSphere(centerWorld,block16,toPlot,toWorld,3.0));
        check(new ChunkPos(15,15).x != new ChunkPos(16,15).x);
        check(!RadarSpearBlastGeometry.intersectsWorldSphere(centerWorld,new AABB(20,64,15,21,65,16),toPlot,toWorld,3.0));
        check(!RadarSpearBlastGeometry.intersectsWorldSphere(centerWorld,block15,toPlot,toWorld,Double.NaN));
        check(RadarSpearBlastGeometry.worldSphereBoundsInPlot(centerWorld,Double.POSITIVE_INFINITY,toPlot)==null);
        // Rotated plot bounds are only an enumeration superset; exact transformed distance decides membership.
        Vec3 farWorld=toWorld.apply(new Vec3(18.0,64.5,15.5));
        check(centerWorld.distanceToSqr(farWorld)>9.0);
        System.out.println("Radar Spear transformed blast geometry regression checks passed");
    }
    private static void check(boolean value) { if(!value) throw new AssertionError("Radar Spear blast geometry regression"); }
}
