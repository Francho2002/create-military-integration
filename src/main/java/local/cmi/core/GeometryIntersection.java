package local.cmi.core;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** AABB.clip alone can miss segments that start or finish inside the box. */
public final class GeometryIntersection {
    private GeometryIntersection() {}
    public static boolean intersects(AABB box, Vec3 start, Vec3 end) {
        return box.contains(start) || box.contains(end) || box.clip(start, end).isPresent();
    }
}
