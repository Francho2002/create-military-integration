package local.cmi.core;

import java.util.UUID;
import dev.ryanhcode.sable.companion.math.Pose3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

public final class MountedBlockReferenceRegression {
    private MountedBlockReferenceRegression() {}

    public static void main(String[] args) {
        ResourceLocation dimension = ResourceLocation.parse("minecraft:overworld");
        BlockPos worldPos = new BlockPos(-16, 72, 33);
        MountedBlockReference world = MountedBlockReference.world(dimension, worldPos);
        check(world.equals(MountedBlockReference.load(world.save()).orElseThrow()), "WORLD reference round trip");
        check(!world.save().hasUUID("ship"), "WORLD reference has no ship UUID");

        UUID ship = UUID.fromString("bb2c28f7-a8f5-4be9-8d16-b647f4450c1d");
        BlockPos local = new BlockPos(17, 80, 34);
        MountedBlockReference sable = MountedBlockReference.sable(dimension, ship, local);
        check(sable.equals(MountedBlockReference.load(sable.save()).orElseThrow()), "SABLE reference round trip");
        MountedBlockReference otherShipSamePosition = MountedBlockReference.sable(dimension,
                UUID.fromString("e5b087ab-c1e0-4ff2-b8e0-b33c08d9fed3"), local);
        check(!sable.equals(otherShipSamePosition), "same local position on different ships is a different reference");
        check(MountedBlockReference.load(new CompoundTag()).isEmpty(), "empty reference rejected");

        CompoundTag missingShip = sable.save();
        missingShip.remove("ship");
        check(MountedBlockReference.load(missingShip).isEmpty(), "SABLE reference without ship UUID rejected");
        CompoundTag worldWithShip = world.save();
        worldWithShip.putUUID("ship", ship);
        check(MountedBlockReference.load(worldWithShip).isEmpty(), "WORLD reference with ship UUID rejected");
        CompoundTag unknownSpace = world.save();
        unknownSpace.putString("space", "PLOT");
        check(MountedBlockReference.load(unknownSpace).isEmpty(), "unknown reference space rejected");

        UUID owner = UUID.fromString("8bef8e23-90a7-4f49-9e70-3f5a3d3f69d9");
        Actor shipActor = new Actor(owner, "", ship);
        CompoundTag blockData = new CompoundTag();
        check(MountedBlockReference.blockOwnerMatchesShip(blockData, shipActor), "missing local owner inherits ship ownership");
        check(owner.equals(MountedBlockReference.sableActor(ship, shipActor, blockData).owner()), "Sable actor uses current CmiData ship owner");
        UUID newOwner = UUID.fromString("b6014a96-5471-4466-b659-5e480f44579d");
        Actor updatedShipActor = new Actor(newOwner, "", ship);
        check(newOwner.equals(MountedBlockReference.sableActor(ship, updatedShipActor, blockData).owner()), "Sable actor reflects a changed ship owner on the next lookup");
        blockData.putUUID("cmiOwner", owner);
        check(MountedBlockReference.blockOwnerMatchesShip(blockData, shipActor), "matching local owner agrees with CmiData ship owner");
        check(MountedBlockReference.sableActor(ship, updatedShipActor, blockData).owner() == null, "stale block owner cannot authorize after ship ownership changes");
        blockData.putUUID("cmiOwner", UUID.fromString("184e82b2-681d-48ce-ae32-9782c605a379"));
        check(!MountedBlockReference.blockOwnerMatchesShip(blockData, shipActor), "stale local owner conflicts with CmiData ship owner");
        blockData.putString("cmiOwner", "malformed");
        check(!MountedBlockReference.blockOwnerMatchesShip(blockData, shipActor), "malformed local owner is rejected");
        check(!MountedBlockReference.blockOwnerMatchesShip(blockData, null), "local owner cannot authorize an unowned ship");

        BlockPos stored = MountedBlockReference.storedBlockPosition(local, new ChunkPos(129, -50), -64, 320);
        check(new BlockPos(2065, 80, -798).equals(stored), "local X/Z map through the selected plot chunk; Y is preserved");
        check(MountedBlockReference.storedBlockPosition(new BlockPos(-1, 80, 0), new ChunkPos(129, -50), -64, 320) == null,
                "negative plot-local X rejected");
        check(MountedBlockReference.storedBlockPosition(new BlockPos(1, 320, 0), new ChunkPos(129, -50), -64, 320) == null,
                "out-of-build-height plot-local Y rejected");
        check(MountedBlockReference.storedBlockPosition(local, new ChunkPos(Integer.MAX_VALUE, 0), -64, 320) == null,
                "overflowing stored coordinate rejected");

        Pose3d firstPose = new Pose3d(new Vector3d(0, 0, 0), new Quaterniond().identity(), new Vector3d(0, 0, 0), new Vector3d(1, 1, 1));
        var firstWorldCenter = MountedBlockReference.worldCenter(firstPose, local);
        firstPose.position().set(10, 0, 0);
        var movedWorldCenter = MountedBlockReference.worldCenter(firstPose, local);
        check(firstWorldCenter != null && movedWorldCenter != null && movedWorldCenter.x - firstWorldCenter.x == 10.0,
                "world center is recomputed from the changed live Sable pose");
        System.out.println("MountedBlockReference regression checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
