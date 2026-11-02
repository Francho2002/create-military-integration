package local.cmi.core;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** Stable, explicit location for a system block in either a parent world or a Sable ship plot. */
public record MountedBlockReference(Space space, ResourceLocation dimension, UUID shipUuid, BlockPos position) {
    public enum Space { WORLD, SABLE }

    public record Resolved(ServerLevel level, BlockPos requestedPosition, BlockPos storedPosition,
                           BlockEntity blockEntity, UUID shipUuid, Vec3 worldCenter) {}

    public MountedBlockReference {
        if (space == null || dimension == null || position == null) throw new IllegalArgumentException("missing reference field");
        if (space == Space.WORLD && shipUuid != null) throw new IllegalArgumentException("WORLD references cannot carry a ship UUID");
        if (space == Space.SABLE && shipUuid == null) throw new IllegalArgumentException("SABLE references require a ship UUID");
        position = position.immutable();
    }

    public static MountedBlockReference world(ResourceLocation dimension, BlockPos position) {
        return new MountedBlockReference(Space.WORLD, dimension, null, position);
    }

    /** Sable X/Z coordinates start at the plot's minimum chunk; Y remains in the parent level's build range. */
    public static MountedBlockReference sable(ResourceLocation dimension, UUID shipUuid, BlockPos localPosition) {
        return new MountedBlockReference(Space.SABLE, dimension, shipUuid, localPosition);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("space", space.name());
        tag.putString("dimension", dimension.toString());
        tag.putLong("position", position.asLong());
        if (shipUuid != null) tag.putUUID("ship", shipUuid);
        return tag;
    }

    public static Optional<MountedBlockReference> load(CompoundTag tag) {
        if (tag == null || !tag.contains("space", Tag.TAG_STRING) || !tag.contains("dimension", Tag.TAG_STRING) ||
                !tag.contains("position", Tag.TAG_LONG)) return Optional.empty();
        ResourceLocation dimension;
        try { dimension = ResourceLocation.parse(tag.getString("dimension")); }
        catch (IllegalArgumentException malformedDimension) { return Optional.empty(); }
        String value = tag.getString("space");
        if (Space.WORLD.name().equals(value)) {
            if (tag.contains("ship")) return Optional.empty();
            return Optional.of(world(dimension, BlockPos.of(tag.getLong("position"))));
        }
        if (Space.SABLE.name().equals(value) && tag.hasUUID("ship")) {
            return Optional.of(sable(dimension, tag.getUUID("ship"), BlockPos.of(tag.getLong("position"))));
        }
        return Optional.empty();
    }

    /** Resolves only existing, already-loaded chunks and block entities. It never requests chunk loads. */
    public Optional<Resolved> resolve(ServerLevel parent) {
        if (parent == null || !parent.getServer().isSameThread()) return Optional.empty();
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, dimension);
        ServerLevel level = parent.getServer().getLevel(dimensionKey);
        if (level == null) return Optional.empty();
        try {
            return space == Space.WORLD ? resolveWorld(level) : resolveSable(level);
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /** Ownership for a Sable-mounted block comes only from the ship UUID's CMI record. */
    public Actor actor(ServerLevel parent) {
        Optional<Resolved> resolved = resolve(parent);
        if (resolved.isEmpty()) return new Actor(null, "", space == Space.SABLE ? shipUuid : null);
        if (space == Space.WORLD) return CombatServices.actor(resolved.get().level(), resolved.get().storedPosition());
        Actor ship = CmiData.get(resolved.get().level()).ship(shipUuid);
        return sableActor(shipUuid, ship, resolved.get().blockEntity().getPersistentData());
    }

    public static Actor sableActor(UUID shipUuid, Actor ship, CompoundTag blockData) {
        if (shipUuid == null || !blockOwnerMatchesShip(blockData, ship) ||
                (ship != null && ship.platform() != null && !shipUuid.equals(ship.platform()))) return new Actor(null, "", shipUuid);
        return new Actor(ship == null ? null : ship.owner(), ship == null || ship.iff() == null ? "" : ship.iff(), shipUuid);
    }

    public static boolean blockOwnerMatchesShip(CompoundTag blockData, Actor ship) {
        if (blockData == null || !blockData.contains("cmiOwner")) return true;
        return ship != null && ship.owner() != null && blockData.hasUUID("cmiOwner") && ship.owner().equals(blockData.getUUID("cmiOwner"));
    }

    /** Uses the ship's live pose for every lookup; cached world coordinates are intentionally not stored. */
    public static Vec3 worldCenter(Pose3dc pose, BlockPos localPosition) {
        if (pose == null || localPosition == null) return null;
        Vec3 center = pose.transformPosition(Vec3.atCenterOf(localPosition));
        return finite(center) ? center : null;
    }

    /** Converts X/Z plot-local coordinates through Sable's pinned chunk translation API. */
    public static BlockPos storedPlotPosition(LevelPlot plot, BlockPos localPosition, int minBuildHeight, int maxBuildHeight) {
        if (plot == null || localPosition == null || localPosition.getX() < 0 || localPosition.getZ() < 0 ||
                localPosition.getY() < minBuildHeight || localPosition.getY() >= maxBuildHeight) return null;
        ChunkPos localChunk = new ChunkPos(localPosition);
        ChunkPos globalChunk = plot.toGlobal(localChunk);
        if (!plot.contains(globalChunk)) return null;
        return storedBlockPosition(localPosition, globalChunk, minBuildHeight, maxBuildHeight);
    }

    public static BlockPos storedBlockPosition(BlockPos localPosition, ChunkPos globalChunk, int minBuildHeight, int maxBuildHeight) {
        if (localPosition == null || globalChunk == null || localPosition.getX() < 0 || localPosition.getZ() < 0 ||
                localPosition.getY() < minBuildHeight || localPosition.getY() >= maxBuildHeight) return null;
        long x = (long) globalChunk.x * 16L + Math.floorMod(localPosition.getX(), 16);
        long z = (long) globalChunk.z * 16L + Math.floorMod(localPosition.getZ(), 16);
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) return null;
        return new BlockPos((int)x, localPosition.getY(), (int)z);
    }

    private Optional<Resolved> resolveWorld(ServerLevel level) {
        if (!level.hasChunkAt(position)) return Optional.empty();
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container != null && container.getPlot(new ChunkPos(position)) != null) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(position);
        return validBlockEntity(level, position, blockEntity)
                ? Optional.of(new Resolved(level, position, position, blockEntity, null, Vec3.atCenterOf(position))) : Optional.empty();
    }

    private Optional<Resolved> resolveSable(ServerLevel level) {
        ServerSubLevelContainer container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return Optional.empty();
        if (!(container.getSubLevel(shipUuid) instanceof ServerSubLevel ship) || ship.isRemoved() ||
                !shipUuid.equals(ship.getUniqueId()) || ship.getLevel() != level) return Optional.empty();
        LevelPlot plot = ship.getPlot();
        BlockPos storedPosition = storedPlotPosition(plot, position, level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (storedPosition == null) return Optional.empty();
        ChunkPos localChunk = new ChunkPos(position);
        LevelChunk chunk = plot.getChunk(localChunk);
        if (chunk == null || chunk.getLevel() != level || !storedPosition.equals(chunk.getPos().getBlockAt(position.getX() & 15, position.getY(), position.getZ() & 15)))
            return Optional.empty();
        BlockEntity blockEntity = chunk.getBlockEntity(storedPosition);
        Vec3 worldCenter = worldCenter(ship.logicalPose(), position);
        return validBlockEntity(level, storedPosition, blockEntity) && worldCenter != null
                ? Optional.of(new Resolved(level, position, storedPosition, blockEntity, shipUuid, worldCenter)) : Optional.empty();
    }

    private static boolean validBlockEntity(ServerLevel level, BlockPos position, BlockEntity blockEntity) {
        return blockEntity != null && !blockEntity.isRemoved() && blockEntity.getLevel() == level && position.equals(blockEntity.getBlockPos());
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
