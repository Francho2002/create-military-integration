package local.cmi.missiles;

import java.util.UUID;
import local.cmi.core.Actor;
import local.cmi.core.TargetId;
import net.minecraft.nbt.CompoundTag;

/** Run without a game to check persistence, identity and replay resistance. */
public final class RadarSpearImpactRegression {
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        require(RadarSpearImpact.validDamage(10));
        require(!RadarSpearImpact.validDamage(0) && !RadarSpearImpact.validDamage(10.01));
        require(!RadarSpearImpact.validDamage(Double.NaN) && !RadarSpearImpact.validDamage(Double.POSITIVE_INFINITY));
        UUID launch = UUID.randomUUID(), owner = UUID.randomUUID(), target = UUID.randomUUID();
        Actor actor = new Actor(owner, "blue", UUID.randomUUID());
        TargetId id = new TargetId(TargetId.Kind.ENTITY, target);
        CompoundTag data = new CompoundTag();
        require(RadarSpearImpact.arm(data, actor, id, launch, 100, false));
        require(!RadarSpearImpact.valid(data, launch, 99));
        require(!RadarSpearImpact.valid(data, launch, 231));
        CompoundTag cancelled = data.copy();
        RadarSpearImpact.cancel(cancelled);
        require(!RadarSpearImpact.valid(cancelled, launch, 100));
        require(RadarSpearImpact.matches(data, launch, 100, actor, id, false));
        require(!RadarSpearImpact.matches(data, launch, 100, actor, id, true));
        require(!RadarSpearImpact.matches(data, launch, 100, actor, new TargetId(TargetId.Kind.ENTITY, UUID.randomUUID()), false));
        CompoundTag saved = data.copy();
        require(RadarSpearImpact.valid(saved, launch, 100));
        require(!RadarSpearImpact.valid(saved, UUID.randomUUID(), 100));
        require(!RadarSpearImpact.arm(saved, actor, id, launch, 100, false));
        require(RadarSpearImpact.consume(saved, launch, 100));
        require(!RadarSpearImpact.consume(saved, launch, 100));
        require(!RadarSpearImpact.arm(saved, actor, id, launch, 100, false));
        CompoundTag bad = data.copy();
        bad.getCompound("cmiRadarImpact").putInt("status", 0);
        require(!RadarSpearImpact.valid(bad, launch, 100));
        require(!RadarSpearImpact.consume(bad, launch, 100));
        require(!RadarSpearImpact.valid(bad, launch, 100));
        CompoundTag corrupted = new CompoundTag();
        corrupted.putString("cmiRadarImpact", "invalid");
        require(!RadarSpearImpact.arm(corrupted, actor, id, launch, 100, false));
        require(!RadarSpearImpact.valid(corrupted, launch, 100));
        require(!RadarSpearImpact.arm(new CompoundTag(), new Actor(null, "", null), id, launch, 100, false));
        CompoundTag interceptor = new CompoundTag();
        require(RadarSpearImpact.arm(interceptor, actor, id, launch, 100, true));
        require(RadarSpearImpact.interceptor(interceptor, launch, 100));
        require(!RadarSpearImpact.matches(interceptor, launch, 100, actor, id, false));
        require(RadarSpearImpact.matches(interceptor, launch, 100, actor, id, true));
        require(!RadarSpearImpact.consume(interceptor, launch, 231));
        require(!RadarSpearImpact.valid(interceptor, launch, 100));
        CompoundTag oneShot = new CompoundTag();
        require(RadarSpearImpact.arm(oneShot, actor, id, launch, 100, false));
        require(RadarSpearImpact.consume(oneShot, launch, 101));
        RadarSpearImpact.cancel(oneShot);
        require("consumed".equals(oneShot.getCompound("cmiRadarImpact").getString("status")));
        System.out.println("Radar Spear impact context regression checks passed");
    }
}
