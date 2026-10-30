package local.cmi.weapons;

import java.util.Optional;
import local.cmi.mixin.weapons.WariumCannonAccess;
import net.ato.shupapium.cannons.MountedShupapiumACContraption;
import net.ato.shupapium.cannons.ShupapiumACBreechBlockEntity;
import net.ato.shupapium.items.ShupapiumAmmoItem;
import net.ato.shupapium.utils.actypes.ShupapiumACFireParams;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import rbasamoyai.createbigcannons.cannons.ItemCannonBehavior;
import rbasamoyai.createbigcannons.cannons.autocannon.IAutocannonBlockEntity;

/** Loaded Warium ammunition and actual configured cannon speed; arrow trajectory is not CBC ballistics. */
public final class WariumLoadout {
    private WariumLoadout() {}
    public record Profile(ItemStack ammunition, float speed, float spread, boolean affectedByGravity, int lifetimeTicks) {}
    public static Optional<Profile> inspect(MountedShupapiumACContraption cannon) {
        WariumCannonAccess access = (WariumCannonAccess) cannon;
        var profile = access.cmi$profile();
        var material = access.cmi$material();
        var layout = access.cmi$layout();
        if (profile == null || material == null || layout == null || cannon.getStartPos() == null ||
            !(cannon.presentBlockEntities.get(cannon.getStartPos()) instanceof ShupapiumACBreechBlockEntity breech) ||
            !breech.canFire() || access.cmi$incompatible(breech)) return Optional.empty();
        ItemStack stack = breech.getInputBuffer().peekFirst();
        if (stack == null || !(stack.getItem() instanceof ShupapiumAmmoItem ammo) || ammo.getMainProperties(stack) == null) return Optional.empty();
        var props = material.properties();
        int length = 0;
        float speedBonus = 0, spreadReduction = 0;
        for (BlockPos pos : layout.barrelPositions()) {
            if (!(cannon.presentBlockEntities.get(pos) instanceof IAutocannonBlockEntity barrel)) continue;
            if (!((ItemCannonBehavior) barrel.cannonBehavior()).canLoadItem(stack)) return Optional.empty();
            if (++length > props.maxBarrelLength()) return Optional.empty();
            if (length <= props.maxSpeedIncreases()) speedBonus += props.speedIncreasePerBarrel();
            spreadReduction += props.spreadReductionPerBarrel();
        }
        var fire = ShupapiumACFireParams.resolve(profile, layout.totalModifiers(), ammo.getBallistics(stack), speedBonus, spreadReduction);
        return Optional.of(new Profile(stack.copy(), fire.speed(), fire.spread(), ammo.projectileAffectedByWorldsGravity(), props.projectileLifetime() * 20));
    }
}
