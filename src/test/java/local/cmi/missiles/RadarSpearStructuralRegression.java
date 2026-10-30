package local.cmi.missiles;

import java.util.UUID;
import local.cmi.core.SableFirstHit;
import local.cmi.core.TargetId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Pure regression for callback identity; does not initialize a world or run the game. */
public final class RadarSpearStructuralRegression {
    public static void main(String[] args) {
        UUID ship = UUID.randomUUID();
        TargetId target = new TargetId(TargetId.Kind.SABLE, ship);
        BlockPos pos = new BlockPos(2, 3, 4);
        Vec3 point = new Vec3(2, 3.5, 4.5);
        BlockHitResult hit = new BlockHitResult(point, Direction.WEST, pos, false);
        var first = new SableFirstHit.Result(SableFirstHit.Kind.TARGET, ship, point, pos, Direction.WEST, null);
        check(RadarSpearStructural.confirmed(first, target, hit, point));
        check(!RadarSpearStructural.confirmed(first, new TargetId(TargetId.Kind.SABLE, UUID.randomUUID()), hit, point));
        check(!RadarSpearStructural.confirmed(first, target, hit, point.add(0.01, 0, 0)));
        check(!RadarSpearStructural.confirmed(new SableFirstHit.Result(SableFirstHit.Kind.OTHER_ENEMY, ship, point, pos, Direction.WEST, first.state()), target, hit, point));
        check(!RadarSpearStructural.confirmed(first, target, new BlockHitResult(point, Direction.EAST, pos, false), point));
        check(!RadarSpearStructural.confirmed(first, target, new BlockHitResult(point, Direction.WEST, pos.offset(1, 0, 0), false), point));
        for (var kind : SableFirstHit.Kind.values()) {
            if (kind != SableFirstHit.Kind.TARGET)
                check(!RadarSpearStructural.confirmed(new SableFirstHit.Result(kind, ship, point, pos, Direction.WEST, null), target, hit, point));
        }
        var pose = new dev.ryanhcode.sable.companion.math.Pose3d(new org.joml.Vector3d(30, 5, -7),
                new org.joml.Quaterniond().rotationY(Math.PI / 2), new org.joml.Vector3d(), new org.joml.Vector3d(1, 1, 1));
        Vec3 worldPoint = pose.transformPosition(point);
        check(pose.transformPositionInverse(worldPoint).distanceToSqr(point) < 1.0e-16);
        var rotated = new SableFirstHit.Result(SableFirstHit.Kind.TARGET, ship, worldPoint, pos, Direction.WEST, null);
        check(RadarSpearStructural.confirmed(rotated, target, hit, pose.transformPosition(hit.getLocation())));
        Vec3 localStart = new Vec3(-2, 0.5, 0.5), localEnd = new Vec3(4, 0.5, 0.5);
        var fullBlock = net.minecraft.world.phys.shapes.Shapes.box(0, 0, 0, 1, 1, 1);
        var nearHit = fullBlock.clip(localStart, localEnd, BlockPos.ZERO);
        var farHit = fullBlock.clip(localStart, localEnd, new BlockPos(2, 0, 0));
        check(nearHit != null && farHit != null && nearHit.getDirection() == Direction.WEST);
        Vec3 rotatedStart = pose.transformPosition(localStart);
        check(rotatedStart.distanceToSqr(pose.transformPosition(nearHit.getLocation()))
                < rotatedStart.distanceToSqr(pose.transformPosition(farHit.getLocation())));
        System.out.println("Radar Spear structural callback identity checks passed");
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
}
