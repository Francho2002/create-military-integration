package local.cmi.missiles;

import java.util.Objects;
import local.cmi.core.Actor;
import local.cmi.core.CombatServices;
import local.cmi.core.SableFirstHit;
import local.cmi.core.TargetId;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;

/** One confirmed Sable contact, limited to Warium's explicit HeavyCrack substitutions. */
public final class RadarSpearStructural {
    private RadarSpearStructural() {}

    static BlockState replacement(BlockState state) {
        if (state.is(Blocks.STONE)) return Blocks.COBBLESTONE.defaultBlockState();
        if (state.is(Blocks.STONE_BRICKS)) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        if (state.is(com.wariumce.init.CrustyChunksModBlocks.REENFORCED_CONCRETE.get()))
            return com.wariumce.init.CrustyChunksModBlocks.CRACKED_CONCRETE.get().defaultBlockState();
        return null;
    }

    /** Invoke only in the server's actual block-hit callback, never from a predicted collision. */
    public static boolean impact(ServerLevel level, Entity missile, BlockHitResult hit, Vec3 from, Vec3 to) {
        if (level == null || missile == null || missile.level() != level || hit == null || !missile.isAlive()
                || !level.getServer().isSameThread()) return false;
        var data = missile.getPersistentData();
        if (!data.contains("cmiManaged", Tag.TAG_BYTE) || !data.getBoolean("cmiManaged")
                || !data.hasUUID("cmiOwner") || !data.hasUUID("cmiTargetId")
                || !"SABLE".equals(data.getString("cmiTargetKind"))
                || !data.contains("Time", Tag.TAG_DOUBLE) || !Double.isFinite(data.getDouble("Time"))
                || data.getDouble("Time") < 10) return false;
        Actor actor = new Actor(data.getUUID("cmiOwner"), data.getString("cmiIff"),
                data.hasUUID("cmiPlatform") ? data.getUUID("cmiPlatform") : null);
        TargetId target = new TargetId(TargetId.Kind.SABLE, data.getUUID("cmiTargetId"));
        if (!RadarSpearImpact.matches(data, missile.getUUID(), level.getGameTime(), actor, target, false)) return false;
        if (!(level.getPlayerByUUID(actor.owner()) instanceof net.minecraft.server.level.ServerPlayer owner) || !owner.isAlive()) return false;
        var container = SubLevelContainer.getContainer(level);
        var ship = container == null ? null : container.getSubLevel(target.uuid());
        if (ship == null || ship.isRemoved() || ship.getLevel() != level || ship.logicalPose() == null) return false;
        var first = SableFirstHit.evaluate(level, actor, target, from, to, missile);
        if (!confirmed(first, target, hit, ship.logicalPose().transformPosition(hit.getLocation())) || first.state() == null || !SableFirstHit.corridorClear(level, actor, from, first, missile)) return false;
        if (!level.hasChunkAt(first.plotPos()) || !SableFirstHit.territoryClear(level, actor, first.worldHit())
                || CombatServices.isProtected(level, actor, Vec3.atCenterOf(first.plotPos()))
                || CombatServices.resolve(level, target).filter(c -> CombatServices.canEngage(level, actor, c)).isEmpty()
                || first.state().hasBlockEntity() || level.getBlockEntity(first.plotPos()) != null || !level.getBlockState(first.plotPos()).equals(first.state())) return false;
        BlockState replacement = replacement(first.state());
        if (replacement == null) return false;
        // Claim the one-shot before calling external listeners, which can re-enter the impact path.
        var frozen = data.copy();
        if (!RadarSpearImpact.consume(data, missile.getUUID(), level.getGameTime())) return false;
        frozen.getCompound("cmiRadarImpact").putString("status", "consumed");
        // Permit protection listeners to veto before the world change; no player tool or drops are used.
        if (CommonHooks.fireBlockBreak(level, owner.gameMode.getGameModeForPlayer(), owner, first.plotPos(), first.state()).isCanceled()) return false;
        var again = SableFirstHit.evaluate(level, actor, target, from, to, missile);
        if (!confirmed(again, target, hit, ship.logicalPose().transformPosition(hit.getLocation()))
                || !Objects.equals(first, again) || !SableFirstHit.corridorClear(level, actor, from, again, missile) || !level.hasChunkAt(first.plotPos())
                || first.state().hasBlockEntity() || level.getBlockEntity(first.plotPos()) != null || !level.getBlockState(first.plotPos()).equals(first.state())
                || !SableFirstHit.territoryClear(level, actor, first.worldHit())
                || CombatServices.isProtected(level, actor, Vec3.atCenterOf(first.plotPos()))
                || CombatServices.resolve(level, target).filter(c -> CombatServices.canEngage(level, actor, c)).isEmpty()
                || !missile.isAlive() || !owner.isAlive() || owner.level() != level
                || level.getPlayerByUUID(actor.owner()) != owner || !frozen.equals(data)) return false;
        return level.setBlock(first.plotPos(), replacement, 3);
    }

    static boolean confirmed(SableFirstHit.Result result, TargetId target, BlockHitResult hit, Vec3 callbackWorld) {
        return result != null && result.kind() == SableFirstHit.Kind.TARGET && target.uuid().equals(result.shipId())
                && result.plotPos() != null && result.plotPos().equals(hit.getBlockPos())
                && result.face() == hit.getDirection() && result.worldHit() != null
                && callbackWorld != null && Double.isFinite(callbackWorld.x) && Double.isFinite(callbackWorld.y)
                && Double.isFinite(callbackWorld.z) && result.worldHit().distanceToSqr(callbackWorld) <= 1.0e-10;
    }
}
