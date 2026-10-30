package local.cmi.missiles;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Convert a validated plot hit exactly once; null denotes inconsistent geometry. */
public final class RadarSpearCollisionCoordinates {
    private RadarSpearCollisionCoordinates() {}

    public static Vec3 worldEndpoint(Vec3 point, BlockPos block, boolean plot, Pose3dc pose) {
        if (!finite(point) || block == null) return null;
        if (!plot) return point;
        if (pose == null || !withinBlock(point, block)) return null;
        Vec3 world = pose.transformPosition(point);
        return finite(world) ? world : null;
    }

    public static boolean withinBlock(Vec3 point, BlockPos block) {
        if (!finite(point) || block == null) return false;
        double e = 1e-5;
        return point.x >= block.getX() - e && point.x <= block.getX() + 1 + e
                && point.y >= block.getY() - e && point.y <= block.getY() + 1 + e
                && point.z >= block.getZ() - e && point.z <= block.getZ() + 1 + e;
    }

    private static boolean finite(Vec3 p) {
        return p != null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z);
    }
}
