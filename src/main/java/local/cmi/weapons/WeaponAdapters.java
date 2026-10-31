package local.cmi.weapons;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import rbasamoyai.createbigcannons.cannons.ItemCannonBehavior;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonAmmoItem;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedRotarycannonContraption;
import riftyboi.cbcmodernwarfare.cannon_control.compact_mount.CompactCannonMountBlockEntity;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.IMediumcannonBlockEntity;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlockEntity;
import riftyboi.cbcmodernwarfare.cannons.rotarycannon.IRotarycannonBlockEntity;
import riftyboi.cbcmodernwarfare.cannons.rotarycannon.breech.AbstractRotarycannonBreechBlockEntity;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonAmmoItem;
import local.cmi.mixin.weapons.MediumMaterialAccess;
import local.cmi.mixin.weapons.RotaryMaterialAccess;
import local.cmi.mixin.weapons.AutocannonMaterialAccess;
import local.cmi.mixin.weapons.ProjectileBallisticsAccess;

/** Read-only Neo loadout inspection. Empty means no verified loaded trajectory. */
public final class WeaponAdapters {
    private WeaponAdapters() {}
    public record Velocity(float blocksPerTick, ItemStack ammunition, double spawnDistance, int lifetimeTicks, float spread) {}

    public static Optional<Velocity> neoVelocity(PitchOrientedContraptionEntity entity) {
        if (entity.getContraption() instanceof MountedMediumcannonContraption cannon) {
            var material = ((MediumMaterialAccess) cannon).cmi$material();
            if (material == null || cannon.getStartPos() == null ||
                !(cannon.presentBlockEntities.get(cannon.getStartPos()) instanceof MediumcannonBreechBlockEntity breech) || !breech.canFire()) return Optional.empty();
            ItemStack ammo = breech.getInputBuffer();
            if (!(ammo.getItem() instanceof MediumcannonAmmoItem)) return Optional.empty();
            var p = material.properties();
            float speed = p.baseSpeed();
            float spread = p.baseSpread();
            BlockPos pos = cannon.getStartPos().relative(cannon.initialOrientation());
            int length = 0;
            while (cannon.presentBlockEntities.get(pos) instanceof IMediumcannonBlockEntity barrel) {
                if (!((ItemCannonBehavior) barrel.cannonBehavior()).canLoadItem(ammo)) return Optional.empty();
                if (++length > p.maxBarrelLength()) return Optional.empty();
                if (length <= p.maxSpeedIncreases()) speed += p.speedIncreasePerBarrel();
                spread = Math.max(spread - p.spreadReductionPerBarrel(), 0.0f);
                pos = pos.relative(cannon.initialOrientation());
            }
            return Optional.of(new Velocity(speed, ammo.copy(), Math.max(0, along(pos, cannon.initialOrientation()) - 0.5), 0, spread));
        }
        if (entity.getContraption() instanceof MountedRotarycannonContraption cannon) {
            var material = ((RotaryMaterialAccess) cannon).cmi$material();
            if (material == null || cannon.getStartPos() == null ||
                !(cannon.presentBlockEntities.get(cannon.getStartPos()) instanceof AbstractRotarycannonBreechBlockEntity breech) || !breech.canFire()) return Optional.empty();
            ItemStack ammo = breech.getInputBuffer().peekFirst();
            if (ammo == null || !(ammo.getItem() instanceof AutocannonAmmoItem)) return Optional.empty();
            var p = material.properties();
            float speed = p.baseSpeed();
            float spread = p.baseSpread();
            BlockPos pos = cannon.getStartPos().relative(cannon.initialOrientation());
            int length = 0;
            while (cannon.presentBlockEntities.get(pos) instanceof IRotarycannonBlockEntity barrel) {
                if (!((ItemCannonBehavior) barrel.cannonBehavior()).canLoadItem(ammo)) return Optional.empty();
                if (++length > p.maxBarrelLength()) return Optional.empty();
                if (length <= p.maxSpeedIncreases()) speed += p.speedIncreasePerBarrel();
                spread = Math.max(spread - p.spreadReductionPerBarrel(), 0.0f);
                pos = pos.relative(cannon.initialOrientation());
            }
            return Optional.of(new Velocity(speed, ammo.copy(), Math.max(0, along(pos, cannon.initialOrientation()) + 1.75), p.projectileLifetime(), spread));
        }
        return Optional.empty();
    }

    private static Optional<Velocity> cbcVelocity(PitchOrientedContraptionEntity entity) {
        if (!(entity.getContraption() instanceof rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption cannon)) return Optional.empty();
        var material = ((AutocannonMaterialAccess)cannon).cmi$material();
        if (material == null || cannon.getStartPos() == null ||
                !(cannon.presentBlockEntities.get(cannon.getStartPos()) instanceof rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity breech) || !breech.canFire()) return Optional.empty();
        ItemStack ammo = breech.getInputBuffer().peekFirst();
        if (ammo == null || !(ammo.getItem() instanceof AutocannonAmmoItem)) return Optional.empty();
        var p = material.properties();
        float speed = p.baseSpeed(), spread = p.baseSpread();
        BlockPos pos = cannon.getStartPos().relative(cannon.initialOrientation());
        int length = 0;
        while (cannon.presentBlockEntities.get(pos) instanceof rbasamoyai.createbigcannons.cannons.autocannon.IAutocannonBlockEntity barrel) {
            if (!((ItemCannonBehavior)barrel.cannonBehavior()).canLoadItem(ammo)) return Optional.empty();
            if (++length > p.maxBarrelLength()) return Optional.empty();
            if (length <= p.maxSpeedIncreases()) speed += p.speedIncreasePerBarrel();
            spread = Math.max(spread - p.spreadReductionPerBarrel(), 0.0f);
            pos = pos.relative(cannon.initialOrientation());
        }
        return Optional.of(new Velocity(speed, ammo.copy(), Math.max(0, along(pos, cannon.initialOrientation()) - 0.5), p.projectileLifetime(), spread));
    }

    /** CIWS permits only exact inspected classes, inert rounds and zero native spread.
     * Rotary's stack can carry Neo Warfare's incendiary flag even when its projectile class is AP. */
    public static Optional<Velocity> ciwsVelocity(PitchOrientedContraptionEntity entity) {
        if (entity == null || entity.getContraption() == null ||
                (entity.getContraption().getClass() != MountedMediumcannonContraption.class &&
                 entity.getContraption().getClass() != MountedRotarycannonContraption.class &&
                 entity.getContraption().getClass() != rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption.class)) return Optional.empty();
        var inspected = entity.getContraption().getClass() == rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption.class ? cbcVelocity(entity) : neoVelocity(entity);
        if (inspected.isEmpty() || !kinetic(entity)) return Optional.empty();
        var v = inspected.get();
        if (!Float.isFinite(v.blocksPerTick()) || v.blocksPerTick() <= 0 ||
                !Float.isFinite(v.spread()) || v.spread() != 0.0f ||
                !Double.isFinite(v.spawnDistance()) || v.spawnDistance() < 0) return Optional.empty();
        var item = v.ammunition().getItem();
        if (item instanceof AutocannonAmmoItem) {
            // Neo Warfare augments both CBC round and cartridge items with this interface.
            // Missing mixin means incendiary status cannot be established safely.
            if (!(item instanceof riftyboi.cbcmodernwarfare.munitions.autocannon.AmmoItemMixinInerface incendiary) ||
                    incendiary.isIncendiary(v.ammunition())) return Optional.empty();
            if (!(entity.level() instanceof ServerLevel level) ||
                    !riftyboi.cbcmodernwarfare.munitions.ItemStackDataUtil.getNestedFuze(v.ammunition(), level.registryAccess()).isEmpty()) return Optional.empty();
        }
        return inspected;
    }

    /** Build the CIWS shell from the real loaded round and the exact native barrel walk.
     * CannonProbe's pinned CBC muzzleDistance is one block beyond CBC's fireShot spawn. */
    public static Optional<com.mrvew.firecontrol.ballistics.ShellModel> ciwsShell(ServerLevel level, PitchOrientedContraptionEntity entity) {
        var v = ciwsVelocity(entity);
        if (v.isEmpty()) return Optional.empty();
        var ammo = v.get().ammunition();
        rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile projectile;
        if (ammo.getItem() instanceof MediumcannonAmmoItem medium) projectile = medium.getMediumcannonProjectile(ammo, level);
        else if (ammo.getItem() instanceof AutocannonAmmoItem automatic) projectile = automatic.getAutocannonProjectile(ammo, level);
        else return Optional.empty();
        if (projectile == null) return Optional.empty();
        var bp = ((ProjectileBallisticsAccess)projectile).cmi$ballistics();
        if (bp == null) return Optional.empty();
        var dim = rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler.getProperties(level);
        return Optional.of(new com.mrvew.firecontrol.ballistics.ShellModel(v.get().blocksPerTick(), bp.gravity() * dim.gravityMultiplier(),
                bp.drag() * dim.dragMultiplier(), bp.isQuadraticDrag(), v.get().spawnDistance(), false, v.get().lifetimeTicks()));
    }

    /** True only for verified, currently loaded inert projectile types; unknown ammunition is false. */
    public static boolean kinetic(PitchOrientedContraptionEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        var contraption = entity.getContraption();
        if (contraption instanceof MountedMediumcannonContraption || contraption instanceof MountedRotarycannonContraption) {
            var v = neoVelocity(entity);
            if (v.isEmpty()) return false;
            var stack = v.get().ammunition();
            if (stack.getItem() instanceof MediumcannonAmmoItem round)
                return inert(round.getMediumcannonProjectile(stack, level));
            if (stack.getItem() instanceof AutocannonAmmoItem round)
                return inert(round.getAutocannonProjectile(stack, level));
            return false;
        }
        if (contraption instanceof rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption cannon) {
            if (cannon.getStartPos() == null || !(cannon.presentBlockEntities.get(cannon.getStartPos()) instanceof rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity breech)) return false;
            var stack = breech.getInputBuffer().peekFirst();
            return stack != null && stack.getItem() instanceof AutocannonAmmoItem round && inert(round.getAutocannonProjectile(stack, level));
        }
        if (contraption instanceof rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption cannon && cannon.getStartPos() != null) {
            var blocks = new java.util.ArrayList<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo>();
            BlockPos pos = cannon.getStartPos();
            for (int i = 0; i < 256; i++, pos = pos.relative(cannon.initialOrientation())) {
                if (!(cannon.presentBlockEntities.get(pos) instanceof rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity bore)) break;
                var info = bore.cannonBehavior().block();
                if (!(info.state().getBlock() instanceof rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock block)) continue;
                blocks.add(info);
                if (block.isComplete(blocks, cannon.initialOrientation())) return inert(block.getProjectile(level, blocks));
            }
        }
        return false;
    }

    static boolean inert(rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile projectile) {
        if (projectile == null) return false;
        Class<?> type = projectile.getClass();
        return type == riftyboi.cbcmodernwarfare.munitions.medium_cannon.ap.APMediumcannonProjectile.class ||
            type == riftyboi.cbcmodernwarfare.munitions.medium_cannon.apds.APDSMediumcannonProjectile.class ||
            type == riftyboi.cbcmodernwarfare.munitions.medium_cannon.apfsds.APFSDSMediumcannonProjectile.class ||
            type == rbasamoyai.createbigcannons.munitions.autocannon.ap_round.APAutocannonProjectile.class ||
            type == rbasamoyai.createbigcannons.munitions.big_cannon.ap_shot.APShotProjectile.class ||
            type == rbasamoyai.createbigcannons.munitions.big_cannon.solid_shot.SolidShotProjectile.class;
    }
    private static int along(BlockPos pos, net.minecraft.core.Direction direction) {
        return pos.getX() * direction.getStepX() + pos.getY() * direction.getStepY() + pos.getZ() * direction.getStepZ();
    }

    /** Caller must apply its authorization, friendly-fire and trajectory checks first. */
    public static boolean fire(ServerLevel level, PitchOrientedContraptionEntity entity) {
        if (entity.level() != level || !(entity.getContraption() instanceof AbstractMountedCannonContraption cannon)) return false;
        local.cmi.core.ShotContext.runManaged(() -> cannon.fireShot(level, entity));
        return true; // dispatched; the cannon itself may reject a shot
    }

    public static PitchOrientedContraptionEntity compactContraption(CompactCannonMountBlockEntity mount) {
        return mount.isRunning() ? mount.getContraption() : null;
    }
    public static void aimCompact(CompactCannonMountBlockEntity mount, float yaw, float pitch) {
        mount.setYaw(yaw);
        mount.setPitch(pitch);
    }
}
