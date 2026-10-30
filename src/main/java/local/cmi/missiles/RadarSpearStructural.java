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
                || !level.getServer().isSameThread()) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=preconditions");
            return false;
        }
        var data = missile.getPersistentData();
        if (!data.contains("cmiManaged", Tag.TAG_BYTE) || !data.getBoolean("cmiManaged")
                || !data.hasUUID("cmiOwner") || !data.hasUUID("cmiTargetId")
                || !"SABLE".equals(data.getString("cmiTargetKind"))
                || !data.contains("Time", Tag.TAG_DOUBLE) || !Double.isFinite(data.getDouble("Time"))
                || data.getDouble("Time") < 10) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=managed_identity_or_arming");
            return false;
        }
        Actor actor = new Actor(data.getUUID("cmiOwner"), data.getString("cmiIff"),
                data.hasUUID("cmiPlatform") ? data.getUUID("cmiPlatform") : null);
        TargetId target = new TargetId(TargetId.Kind.SABLE, data.getUUID("cmiTargetId"));
        if (!RadarSpearImpact.matchesProfile(data, missile.getUUID(), level.getGameTime(), actor, target, "direct_sable")) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=profile_authorization");
            return false;
        }
        if (!(level.getPlayerByUUID(actor.owner()) instanceof net.minecraft.server.level.ServerPlayer owner) || !owner.isAlive()) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=owner_unavailable");
            return false;
        }
        var container = SubLevelContainer.getContainer(level);
        var ship = container == null ? null : container.getSubLevel(target.uuid());
        if (ship == null || ship.isRemoved() || ship.getLevel() != level || ship.logicalPose() == null) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=designated_sable_unavailable");
            return false;
        }
        long queryTick = level.getGameTime();
        var pose = new dev.ryanhcode.sable.companion.math.Pose3d(ship.logicalPose());
        var first = SableFirstHit.evaluate(level, actor, target, from, to, missile);
        RadarSpearDiagnostics.firstHit(missile, first);
        if (!queryMatches(first, queryTick, pose, ship.logicalPose())) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=first_hit_pose_or_tick");
            return false;
        }
        if (!confirmed(first, target, hit, pose.transformPosition(hit.getLocation()))) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=native_callback_mismatch callbackBlock="
                    + hit.getBlockPos() + " callbackFace=" + hit.getDirection() + " callbackLocal=" + RadarSpearDiagnostics.vec(hit.getLocation()));
            return false;
        }
        if (first.state() == null || !SableFirstHit.corridorClear(level, actor, from, first, missile)) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=block_state_or_corridor");
            return false;
        }
        if (!level.hasChunkAt(first.plotPos()) || !SableFirstHit.territoryClear(level, actor, first.worldHit())
                || CombatServices.isProtected(level, actor, Vec3.atCenterOf(first.plotPos()))
                || CombatServices.resolve(level, target).filter(c -> CombatServices.canEngage(level, actor, c)).isEmpty()
                || first.state().hasBlockEntity() || level.getBlockEntity(first.plotPos()) != null || !level.getBlockState(first.plotPos()).equals(first.state())) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=protection_or_current_block_state");
            return false;
        }
        BlockState replacement = replacement(first.state());
        if (replacement == null) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=block_not_in_structural_profile state=" + first.state());
            return false;
        }
        if (level.getGameTime() != queryTick || container.getSubLevel(target.uuid()) != ship || ship.isRemoved()
                || ship.getLevel() != level || !queryMatches(first, queryTick, pose, ship.logicalPose())) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=preconsume_revalidation");
            return false;
        }
        RadarSpearDiagnostics.event(missile, "impact_confirmed", "profile=direct_sable stage=callback_and_authorization ship="
                + first.shipId() + " world=" + RadarSpearDiagnostics.vec(first.worldHit()) + " plot="
                + RadarSpearDiagnostics.vec(first.plotHit()) + " block=" + first.plotPos() + " face=" + first.face());
        // Claim the one-shot before calling external listeners, which can re-enter the impact path.
        var frozen = data.copy();
        if (!RadarSpearImpact.consume(data, missile.getUUID(), level.getGameTime())) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=consume_failed");
            return false;
        }
        RadarSpearDiagnostics.event(missile, "impact_consumed", "profile=direct_sable block=" + first.plotPos());
        frozen.getCompound("cmiRadarImpact").putString("status", "consumed");
        // Permit protection listeners to veto before the world change; no player tool or drops are used.
        if (CommonHooks.fireBlockBreak(level, owner.gameMode.getGameModeForPlayer(), owner, first.plotPos(), first.state()).isCanceled()) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=block_break_listener_veto");
            return false;
        }
        // External listeners may change the ship, pose or tick.
        if (level.getGameTime() != queryTick || container.getSubLevel(target.uuid()) != ship || ship.isRemoved()
                || ship.getLevel() != level || ship.logicalPose() == null || !samePose(pose, ship.logicalPose())) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=listener_pose_revalidation");
            return false;
        }
        var again = SableFirstHit.evaluate(level, actor, target, from, to, missile);
        if (!queryMatches(again, queryTick, pose, ship.logicalPose()) || !confirmed(again, target, hit, pose.transformPosition(hit.getLocation()))
                || !sameHit(first, again) || !SableFirstHit.corridorClear(level, actor, from, again, missile) || !level.hasChunkAt(first.plotPos())
                || first.state().hasBlockEntity() || level.getBlockEntity(first.plotPos()) != null || !level.getBlockState(first.plotPos()).equals(first.state())
                || !SableFirstHit.territoryClear(level, actor, first.worldHit())
                || CombatServices.isProtected(level, actor, Vec3.atCenterOf(first.plotPos()))
                || CombatServices.resolve(level, target).filter(c -> CombatServices.canEngage(level, actor, c)).isEmpty()
                || !missile.isAlive() || !owner.isAlive() || owner.level() != level
                || level.getPlayerByUUID(actor.owner()) != owner || !frozen.equals(data)) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=postlistener_revalidation");
            return false;
        }
        if (level.getGameTime() != queryTick || container.getSubLevel(target.uuid()) != ship || ship.isRemoved()
                || ship.getLevel() != level || !queryMatches(again, queryTick, pose, ship.logicalPose())) {
            RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=final_pose_revalidation");
            return false;
        }
        boolean changed = level.setBlock(first.plotPos(), replacement, 3);
        if (changed) RadarSpearDiagnostics.event(missile, "block_changed", "profile=direct_sable block=" + first.plotPos()
                + " before=" + first.state() + " after=" + replacement + " count=1");
        else RadarSpearDiagnostics.event(missile, "impact_rejected", "profile=direct_sable stage=set_block_returned_false");
        return changed;
    }

    private static boolean sameHit(SableFirstHit.Result a, SableFirstHit.Result b) {
        return a.kind() == b.kind() && Objects.equals(a.shipId(), b.shipId())
                && Objects.equals(a.worldHit(), b.worldHit()) && Objects.equals(a.plotHit(), b.plotHit())
                && Objects.equals(a.plotPos(), b.plotPos()) && a.face() == b.face() && Objects.equals(a.state(), b.state());
    }

    static boolean queryMatches(SableFirstHit.Result result, long tick,
            dev.ryanhcode.sable.companion.math.Pose3dc expected, dev.ryanhcode.sable.companion.math.Pose3dc current) {
        return result != null && result.queryTick() == tick && result.pose() != null && expected != null && current != null
                && samePose(expected, result.pose()) && samePose(expected, current);
    }

    static boolean samePose(dev.ryanhcode.sable.companion.math.Pose3dc a, dev.ryanhcode.sable.companion.math.Pose3dc b) {
        return a.position().equals(b.position()) && a.orientation().equals(b.orientation())
                && a.rotationPoint().equals(b.rotationPoint()) && a.scale().equals(b.scale());
    }

    static boolean confirmed(SableFirstHit.Result result, TargetId target, BlockHitResult hit, Vec3 callbackWorld) {
        return result != null && result.kind() == SableFirstHit.Kind.TARGET && target.uuid().equals(result.shipId())
                && result.plotPos() != null && result.plotPos().equals(hit.getBlockPos())
                && result.face() == hit.getDirection() && result.worldHit() != null
                && result.plotHit() != null && result.plotHit().distanceToSqr(hit.getLocation()) <= 1.0e-10
                && callbackWorld != null && Double.isFinite(callbackWorld.x) && Double.isFinite(callbackWorld.y)
                && Double.isFinite(callbackWorld.z) && result.worldHit().distanceToSqr(callbackWorld) <= 1.0e-10;
    }
}
