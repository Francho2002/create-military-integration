package local.cmi.mixin.missiles;

import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import java.util.Comparator;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import com.mrvew.firecontrol.ship.Ships;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import local.cmi.core.CombatServices;
import local.cmi.core.Actor;
import local.cmi.core.MilitaryContact;
import local.cmi.core.TargetId;
import local.cmi.core.TrajectorySafety;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import riftyboi.cbcmodernwarfare.munitions.contraptions.MunitionsPhysicsContraptionEntity;
import riftyboi.cbcmodernwarfare.munitions.munitions_contraption_launcher.guidance.infrared_homing.InfraredSeekerGuidanceBlock;

/** Adapts the native IR seeker to server contacts while retaining its turn model. */
@Mixin(targets = "riftyboi.cbcmodernwarfare.munitions.munitions_contraption_launcher.guidance.infrared_homing.InfraredSeekerGuidanceBlockEntity", remap = false)
public abstract class InfraredGuidanceMixin {
    @Shadow private boolean isLockedOn;
    @Shadow private int trackedEntityId;
    @Shadow private BlockPos currentPos;
    @Shadow protected abstract double calculateTurningSpeed(double size, double speed, BlockState state);
    private static final String ID = "cmiTargetId";
    private static final String KIND = "cmiTargetKind";

    private CompoundTag cmi$data() { return ((BlockEntity) (Object) this).getPersistentData(); }
    private void cmi$clear() { cmi$data().remove(ID); cmi$data().remove(KIND); isLockedOn = false; trackedEntityId = -1; }
    private Optional<TargetId> cmi$id() {
        CompoundTag tag = cmi$data();
        if (!tag.hasUUID(ID)) return Optional.empty();
        try { return Optional.of(new TargetId(TargetId.Kind.valueOf(tag.getString(KIND)), tag.getUUID(ID))); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    @Inject(method = "tickGuidance", at = @At("HEAD"), cancellable = true, remap = false)
    private void cmi$acquire(Level level, BlockPos localPos, BlockState state, BlockEntity blockEntity, OrientedContraptionEntity carrier, CallbackInfo ci) {
        ci.cancel();
        if (!(level instanceof ServerLevel server) || !(state.getBlock() instanceof InfraredSeekerGuidanceBlock seeker)) return;
        // The projectile keeps its launch designation. Never silently switch to a new target in flight.
        if (carrier instanceof MunitionsPhysicsContraptionEntity) return;
        Actor actor = CombatServices.actor(server, carrier);
        if (actor.owner() == null && carrier instanceof PitchOrientedContraptionEntity pitch && pitch.getController() instanceof BlockEntity controller)
            actor = CombatServices.actor(server, controller.getBlockPos());
        final Actor launcher = actor;
        boolean interceptor = cmi$data().getBoolean("cmiInterceptor");
        if (actor.owner() == null || currentPos == null) { cmi$clear(); return; }
        var platform = Ships.containing(level, carrier.position());
        Vec3 origin = Ships.toWorld(platform, carrier.toGlobalVector(Vec3.atCenterOf(localPos.relative(carrier.getInitialOrientation())), 0));
        Vec3 muzzle = Ships.toWorld(platform, carrier.toGlobalVector(Vec3.atCenterOf(currentPos.relative(carrier.getInitialOrientation())), 0));
        Vec3 pivot = Ships.toWorld(platform, carrier.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 0));
        Vec3 forward = muzzle.subtract(pivot).normalize();
        double range = seeker.getRange();
        double fov = Math.cos(Math.toRadians(seeker.lockFov() / 2));
        boolean designated = cmi$data().getBoolean("cmiDesignated");
        Optional<TargetId> requested = cmi$id();
        if (designated && requested.isEmpty()) { cmi$clear(); return; }
        Optional<MilitaryContact> selected = CombatServices.contacts(server).stream()
                .filter(c -> !designated || requested.get().equals(c.id()))
                .filter(c -> (interceptor ? c.projectile() && c.velocity().dot(c.position().subtract(origin)) < 0 : c.hot()) && CombatServices.canEngage(server, launcher, c))
                .filter(c -> c.position().distanceToSqr(origin) <= range * range)
                .filter(c -> c.position().subtract(origin).normalize().dot(forward) >= fov)
                .filter(c -> level.clip(new ClipContext(origin, c.position(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, carrier)).getType() == HitResult.Type.MISS)
                .min(Comparator.comparingDouble(c -> c.position().distanceToSqr(origin)));
        if (selected.isEmpty()) { cmi$clear(); return; }
        TargetId id = selected.get().id();
        if (interceptor) {
            UUID lease = UUID.randomUUID();
            if (!CombatServices.reserve(server, id, lease, 2)) { cmi$clear(); return; }
            cmi$data().putUUID("cmiLaunchLease", lease);
        }
        cmi$data().putUUID("cmiOwner", actor.owner());
        cmi$data().putString("cmiIff", actor.iff() == null ? "" : actor.iff());
        if (actor.platform() != null) cmi$data().putUUID("cmiPlatform", actor.platform()); else cmi$data().remove("cmiPlatform");
        cmi$data().putUUID(ID, id.uuid());
        cmi$data().putString(KIND, id.kind().name());
        trackedEntityId = -1;
        isLockedOn = true;
    }

    @Inject(method = "guideEntity", at = @At("HEAD"), cancellable = true, remap = false)
    private void cmi$guide(MunitionsPhysicsContraptionEntity missile, BlockState state, CallbackInfo ci) {
        ci.cancel();
        if (!(missile.level() instanceof ServerLevel server) || !(state.getBlock() instanceof InfraredSeekerGuidanceBlock seeker)) return;
        CompoundTag tag = cmi$data();
        if (!tag.hasUUID("cmiOwner")) { cmi$clear(); return; }
        Actor actor = new Actor(tag.getUUID("cmiOwner"), tag.getString("cmiIff"), tag.hasUUID("cmiPlatform") ? tag.getUUID("cmiPlatform") : null);
        missile.getPersistentData().putUUID("cmiOwner", actor.owner());
        missile.getPersistentData().putString("cmiIff", actor.iff());
        if (actor.platform() != null) missile.getPersistentData().putUUID("cmiPlatform", actor.platform());
        Optional<MilitaryContact> found = cmi$id().flatMap(id -> CombatServices.resolve(server, id));
        boolean interceptor = tag.getBoolean("cmiInterceptor");
        if (actor.owner() == null || found.isEmpty() || (interceptor ? !found.get().projectile() : !found.get().hot()) || !CombatServices.canEngage(server, actor, found.get())) { cmi$clear(); return; }
        MilitaryContact target = found.get();
        if (!TrajectorySafety.clear(server, actor, List.of(missile.position(), missile.position().add(missile.getDeltaMovement())), missile.getBbWidth() * 0.5, missile)) {
            if (interceptor) CombatServices.release(server, target.id(), missile.getUUID());
            cmi$clear(); missile.discard(); return;
        }
        Vec3 offset = target.position().subtract(missile.position());
        double range = seeker.getRange();
        if (offset.lengthSqr() > range * range || server.clip(new ClipContext(missile.position(), target.position(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, missile)).getType() != HitResult.Type.MISS) {
            if (interceptor) CombatServices.release(server, target.id(), missile.getUUID());
            cmi$clear();
            return;
        }
        if (interceptor) {
            if (tag.hasUUID("cmiLaunchLease")) {
                CombatServices.release(server, target.id(), tag.getUUID("cmiLaunchLease"));
                tag.remove("cmiLaunchLease");
            }
            if (!CombatServices.reserve(server, target.id(), missile.getUUID(), 2)) { cmi$clear(); return; }
            if ((missile.getBoundingBox().intersects(target.bounds()) || target.bounds().inflate(missile.getBbWidth() * 0.5).clip(missile.position().subtract(missile.getDeltaMovement()), missile.position()).isPresent()) && !CombatServices.isProtected(server, actor, target.position())) {
                if (CombatServices.neutralize(server, actor, target)) {
                    CombatServices.release(server, target.id(), missile.getUUID());
                    // The upstream blast radius is not available here: intercept without a blast.
                    missile.discard();
                    cmi$clear();
                }
                return;
            }
        }
        Vec3 motion = missile.getDeltaMovement();
        double speed = motion.length();

        if (!Double.isFinite(speed) || speed < 1.0e-6 || offset.lengthSqr() < 1.0e-12) return;
        Vec3 direction = motion.scale(1 / speed);
        if (direction.dot(offset.normalize()) < Math.cos(Math.toRadians(seeker.trackFov() / 2))) { cmi$clear(); return; }
        double lead = offset.length() / speed;
        Vec3 aim = offset.add(target.velocity().scale(lead)).normalize();
        double turn = Math.max(0, Math.min(1, calculateTurningSpeed(missile.getContraption().getBlocks().size(), speed, state)));
        Vec3 steered = direction.lerp(aim, turn);
        if (steered.lengthSqr() > 1.0e-12) {
            Vec3 next = steered.normalize().scale(speed);
            if (!TrajectorySafety.clear(server, actor, List.of(missile.position(), missile.position().add(next)), missile.getBbWidth() * 0.5, missile)) {
                if (interceptor) CombatServices.release(server, target.id(), missile.getUUID());
                cmi$clear(); missile.discard(); return;
            }
            missile.setContraptionMotion(next);
        }
        isLockedOn = true;
    }
}
