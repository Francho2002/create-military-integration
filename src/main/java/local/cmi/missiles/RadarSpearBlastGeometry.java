package local.cmi.missiles;

import java.util.function.UnaryOperator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Small, testable geometry helpers for the CMI-only block effect envelope. */
public final class RadarSpearBlastGeometry {
    private RadarSpearBlastGeometry() {}

    /** Conservative plot-space enumeration bound for a sphere defined in world-space. */
    public static AABB worldSphereBoundsInPlot(Vec3 worldCenter, double radius, UnaryOperator<Vec3> worldToPlot) {
        if (!finite(worldCenter) || !Double.isFinite(radius) || radius <= 0 || worldToPlot == null) return null;
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
        double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for (int x=-1;x<=1;x+=2) for (int y=-1;y<=1;y+=2) for (int z=-1;z<=1;z+=2) {
            Vec3 corner = worldToPlot.apply(worldCenter.add(x*radius,y*radius,z*radius));
            if (!finite(corner)) return null;
            minX=Math.min(minX,corner.x); minY=Math.min(minY,corner.y); minZ=Math.min(minZ,corner.z);
            maxX=Math.max(maxX,corner.x); maxY=Math.max(maxY,corner.y); maxZ=Math.max(maxZ,corner.z);
        }
        return new AABB(minX,minY,minZ,maxX,maxY,maxZ);
    }

    /** Exact nearest-point test for a transformed local AABB under Sable's pose transform. */
    public static boolean intersectsWorldSphere(Vec3 worldCenter, AABB plotBox,
            UnaryOperator<Vec3> worldToPlot, UnaryOperator<Vec3> plotToWorld, double radius) {
        if (!finite(worldCenter) || plotBox == null || worldToPlot == null || plotToWorld == null
                || !Double.isFinite(radius) || radius <= 0) return false;
        Vec3 localCenter=worldToPlot.apply(worldCenter);
        if (!finite(localCenter)) return false;
        Vec3 closest=new Vec3(clamp(localCenter.x,plotBox.minX,plotBox.maxX),
                clamp(localCenter.y,plotBox.minY,plotBox.maxY),clamp(localCenter.z,plotBox.minZ,plotBox.maxZ));
        Vec3 closestWorld=plotToWorld.apply(closest);
        return finite(closestWorld) && worldCenter.distanceToSqr(closestWorld)<=radius*radius+1.0e-9;
    }

    private static double clamp(double value,double min,double max) { return Math.max(min,Math.min(max,value)); }
    public static boolean finite(Vec3 value) {
        return value!=null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
