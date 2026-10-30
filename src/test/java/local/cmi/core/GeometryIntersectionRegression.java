package local.cmi.core;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** No Minecraft game or server is started. */
public final class GeometryIntersectionRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        var box = new AABB(0, 0, 0, 1, 1, 1);
        check(GeometryIntersection.intersects(box, new Vec3(0.5, 0.5, 0.5), new Vec3(2, 0.5, 0.5)), "starts inside");
        check(GeometryIntersection.intersects(box, new Vec3(-1, 0.5, 0.5), new Vec3(0.5, 0.5, 0.5)), "ends inside");
        check(GeometryIntersection.intersects(box, new Vec3(-1, 0.5, 0.5), new Vec3(2, 0.5, 0.5)), "crosses obstacle");
        check(!GeometryIntersection.intersects(box, new Vec3(-1, 2, 0.5), new Vec3(2, 2, 0.5)), "clear path");
        System.out.println("GeometryIntersection regression passed");
    }
}
