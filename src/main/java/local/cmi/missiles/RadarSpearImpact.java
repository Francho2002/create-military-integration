package local.cmi.missiles;

import java.util.UUID;
import local.cmi.core.Actor;
import local.cmi.core.CombatServices;
import local.cmi.core.TargetId;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Persisted, one-shot authorization for the deliberately narrow managed impact profile. */
public final class RadarSpearImpact {
    private static final String KEY = "cmiRadarImpact";
    private static final int VERSION = 1;
    private RadarSpearImpact() {}

    /** Must be called at launch, before the missile is exposed to collisions. Never resets a context. */
    public static boolean arm(CompoundTag data, Actor actor, TargetId target, UUID missileId, long now, boolean interceptor) {
        if (data.contains(KEY) || actor == null || actor.owner() == null || target == null
                || target.kind() == null || target.uuid() == null || missileId == null || now < 0 || now > Long.MAX_VALUE - 130) return false;
        CompoundTag context = new CompoundTag();
        context.putInt("version", VERSION);
        context.putUUID("launch", missileId);
        context.putUUID("effect", UUID.randomUUID());
        context.putLong("created", now);
        context.putLong("expires", now + 130);
        context.putString("munition", "wariumce:radar_spear_missile_projectile");
        context.putString("authorization", "designated_enemy");
        context.putUUID("owner", actor.owner());
        context.putString("iff", actor.iff() == null ? "" : actor.iff());
        if (actor.platform() != null) context.putUUID("platform", actor.platform());
        context.putUUID("target", target.uuid());
        context.putString("kind", target.kind().name());
        context.putString("profile", interceptor ? "interceptor" : target.kind() == TargetId.Kind.SABLE ? "direct_sable" : "direct_living_base");
        context.putString("status", "active");
        data.put(KEY, context);
        return true;
    }

    public static boolean valid(CompoundTag data, UUID missileId, long now) {
        if (missileId == null || !data.contains(KEY, Tag.TAG_COMPOUND)) return false;
        CompoundTag c = data.getCompound(KEY);
        if (!c.contains("version", Tag.TAG_INT) || c.getInt("version") != VERSION
                || !c.hasUUID("launch") || !missileId.equals(c.getUUID("launch"))
                || !c.hasUUID("owner") || !c.hasUUID("target") || !c.hasUUID("effect")
                || !c.contains("created", Tag.TAG_LONG) || !c.contains("expires", Tag.TAG_LONG)
                || c.getLong("created") < 0 || c.getLong("expires") < c.getLong("created")
                || c.getLong("expires") - c.getLong("created") != 130 || now < c.getLong("created") || now > c.getLong("expires")
                || !c.contains("munition", Tag.TAG_STRING) || !"wariumce:radar_spear_missile_projectile".equals(c.getString("munition"))
                || !c.contains("authorization", Tag.TAG_STRING) || !"designated_enemy".equals(c.getString("authorization"))
                || !c.contains("iff", Tag.TAG_STRING) || !c.contains("kind", Tag.TAG_STRING)
                || !c.contains("profile", Tag.TAG_STRING)
                || !("direct_living_base".equals(c.getString("profile")) || "direct_sable".equals(c.getString("profile")) || "interceptor".equals(c.getString("profile")))
                || !c.contains("status", Tag.TAG_STRING) || !"active".equals(c.getString("status"))
                || (c.contains("platform") && !c.hasUUID("platform"))) return false;
        try { TargetId.Kind.valueOf(c.getString("kind")); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    /** Match mutable guidance against the captured authorization before using it. */
    public static boolean matches(CompoundTag data, UUID missileId, long now, Actor actor, TargetId target, boolean interceptor) {
        if (!valid(data, missileId, now) || actor == null || actor.owner() == null || target == null || target.kind() == null || target.uuid() == null) return false;
        CompoundTag c = data.getCompound(KEY);
        return actor.owner().equals(c.getUUID("owner"))
                && java.util.Objects.equals(actor.iff() == null ? "" : actor.iff(), c.getString("iff"))
                && java.util.Objects.equals(actor.platform(), c.hasUUID("platform") ? c.getUUID("platform") : null)
                && target.uuid().equals(c.getUUID("target")) && target.kind().name().equals(c.getString("kind"))
                && (interceptor ? "interceptor".equals(c.getString("profile"))
                    : target.kind() == TargetId.Kind.SABLE
                        ? ("direct_sable".equals(c.getString("profile")) || "direct_living_base".equals(c.getString("profile")))
                        : "direct_living_base".equals(c.getString("profile")));
    }
    public static boolean matches(CompoundTag data, UUID missileId, long now, Actor actor, TargetId target) {
        return matches(data, missileId, now, actor, target, interceptor(data, missileId, now));
    }

    public static boolean interceptor(CompoundTag data, UUID missileId, long now) {
        return valid(data, missileId, now) && "interceptor".equals(data.getCompound(KEY).getString("profile"));
    }
    /** Consume even on a miss or rejected authorization; never re-arm on a repeated callback. */
    public static boolean consume(CompoundTag data, UUID missileId, long now) {
        boolean authorized = valid(data, missileId, now);
        if (data.contains(KEY, Tag.TAG_COMPOUND)) data.getCompound(KEY).putString("status", "consumed");
        return authorized;
    }

    public static void cancel(CompoundTag data) {
        if (data.contains(KEY, Tag.TAG_COMPOUND) && !"consumed".equals(data.getCompound(KEY).getString("status"))) data.getCompound(KEY).putString("status", "cancelled");
    }

    static boolean validDamage(double value) { return Double.isFinite(value) && value > 0 && value <= 10; }

    /** Only call for an actual server-side direct collision after the arming delay. */
    public static boolean impact(ServerLevel level, Entity missile, Entity hit) {
        CompoundTag data = missile.getPersistentData();
        if (!consume(data, missile.getUUID(), level.getGameTime()) || hit == null || !hit.isAlive()) return false;
        CompoundTag c = data.getCompound(KEY);
        if (!"direct_living_base".equals(c.getString("profile")) || !"ENTITY".equals(c.getString("kind")) || !hit.getUUID().equals(c.getUUID("target"))
                || !(hit instanceof net.minecraft.world.entity.LivingEntity) || CombatServices.projectile(hit)) return false;
        Actor actor = new Actor(c.getUUID("owner"), c.getString("iff"),
                c.hasUUID("platform") ? c.getUUID("platform") : null);
        if (CombatServices.isProtected(level, actor, missile.position())
                || CombatServices.isProtected(level, actor, hit.position())) return false;
        TargetId id = new TargetId(TargetId.Kind.ENTITY, hit.getUUID());
        var target = CombatServices.resolve(level, id);
        if (target.isEmpty() || target.get().projectile() || !CombatServices.canEngage(level, actor, target.get())) return false;
        // Attribution is required: if the launch owner is offline or elsewhere, fail closed.
        var owner = level.getPlayerByUUID(actor.owner());
        if (owner == null || !owner.isAlive()) return false;
        if (!(missile instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow)) return false;
        double damage = arrow.getBaseDamage();
        if (!validDamage(damage)) return false;
        return hit.hurt(level.damageSources().arrow(arrow, owner), (float) damage);
    }
}
