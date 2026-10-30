package local.cmi.missiles;

import com.mojang.logging.LogUtils;
import java.util.UUID;
import local.cmi.core.SableFirstHit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Low-volume server diagnostics for explicitly managed Radar Spear launches. */
public final class RadarSpearDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONTEXT = "cmiRadarImpact";

    private RadarSpearDiagnostics() {}

    /** Logs a requested managed launch before it has an armed impact context. Never call for native mode. */
    public static void requested(Entity missile, String event, String detail) {
        if (missile != null) emit(missile.getPersistentData(), missile.getUUID(), event, detail);
    }

    /** Logs only after the projectile carries CMI's explicit managed marker/context. */
    public static void event(Entity missile, String event, String detail) {
        if (missile == null) return;
        CompoundTag data = missile.getPersistentData();
        if (RadarSpearMode.managed(data)) emit(data, missile.getUUID(), event, detail);
    }

    /** Persist a diagnostic state so moving contacts do not produce a line every tick. */
    public static void transition(Entity missile, String marker, String event, String state, String detail) {
        if (missile == null) return;
        CompoundTag data = missile.getPersistentData();
        if (!RadarSpearMode.managed(data) || state.equals(data.getString(marker))) return;
        data.putString(marker, state);
        emit(data, missile.getUUID(), event, detail);
    }

    public static void guidanceActive(Entity missile) {
        if (missile == null) return;
        CompoundTag data = missile.getPersistentData();
        if (!RadarSpearMode.managed(data) || data.getBoolean("cmiDiagGuidanceLogged")) return;
        data.putBoolean("cmiDiagGuidanceLogged", true);
        emit(data, missile.getUUID(), "guidance_active", "time=" + data.getDouble("Time"));
    }

    public static void firstHit(Entity missile, SableFirstHit.Result hit) {
        logFirstHit(missile, hit, "sable_first_hit");
    }

    public static void firstHitTransition(Entity missile, SableFirstHit.Result hit) {
        logFirstHit(missile, hit, "transition");
    }

    private static void logFirstHit(Entity missile, SableFirstHit.Result hit, String mode) {
        if (hit == null) {
            if ("transition".equals(mode)) transition(missile, "cmiDiagFirstHit", "sable_first_hit", "null", "result=null");
            else event(missile, "sable_first_hit", "result=null");
            return;
        }
        String state = hit.kind() + ":" + shortId(hit.shipId()) + ":" + hit.plotPos() + ":" + hit.face();
        String detail = "kind=" + hit.kind() + " ship=" + shortId(hit.shipId())
                + " world=" + vec(hit.worldHit()) + " plot=" + vec(hit.plotHit())
                + " block=" + hit.plotPos() + " face=" + hit.face()
                + " tick=" + hit.queryTick();
        if ("transition".equals(mode)) transition(missile, "cmiDiagFirstHit", "sable_first_hit", state, detail);
        else event(missile, "sable_first_hit", detail);
    }

    public static String vec(Vec3 point) {
        if (point == null) return "null";
        return String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f", point.x, point.y, point.z);
    }

    private static void emit(CompoundTag data, UUID missile, String event, String detail) {
        CompoundTag context = data.contains(CONTEXT, Tag.TAG_COMPOUND) ? data.getCompound(CONTEXT) : new CompoundTag();
        String launch = shortId(context.hasUUID("launch") ? context.getUUID("launch") : missile);
        String effect = shortId(context.hasUUID("effect") ? context.getUUID("effect") : null);
        String profile = context.contains("profile", Tag.TAG_STRING) ? context.getString("profile") : data.getString("cmiRadarProfile");
        String target = shortId(context.hasUUID("target") ? context.getUUID("target") : data.hasUUID("cmiTargetId") ? data.getUUID("cmiTargetId") : null);
        LOGGER.info("[CMI-RadarSpear] event={} launch={} effect={} missile={} profile={} target={} time={} {}",
                event, launch, effect, shortId(missile), profile.isEmpty() ? "-" : profile,
                target, data.contains("Time", Tag.TAG_DOUBLE) ? data.getDouble("Time") : "-", detail == null ? "" : detail);
    }

    private static String shortId(UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }
}
