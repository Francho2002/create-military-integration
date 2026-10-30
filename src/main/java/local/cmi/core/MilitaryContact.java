package local.cmi.core;
import java.util.UUID;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
public record MilitaryContact(TargetId id, Vec3 position, Vec3 velocity, AABB bounds,
        UUID owner, UUID platform, boolean projectile, boolean hot, long seenTick) {}
