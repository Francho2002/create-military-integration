package local.cmi.mixin.missiles;

import com.wariumce.util.BlockWeaponRecoil;
import local.cmi.missiles.RadarSpearBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.wariumce.procedures.MissileHardpointFireProcedure", remap = false)
public abstract class RadarSpearLaunchMixin {
    @Redirect(method = "execute", at = @At(value = "INVOKE", target = "Lcom/wariumce/util/BlockWeaponRecoil;spawn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/core/BlockPos;Lcom/wariumce/util/BlockWeaponRecoil$Weapon;)Z", ordinal = 3, remap = false), remap = false)
    private static boolean cmi$captureLaunch(Level level, Projectile projectile, BlockPos pos, BlockWeaponRecoil.Weapon weapon) {
        if (level instanceof ServerLevel server && !RadarSpearBridge.captureLaunch(server, projectile, pos)) return false;
        return BlockWeaponRecoil.spawn(level, projectile, pos, weapon);
    }
}
