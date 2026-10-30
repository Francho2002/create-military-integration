package local.cmi.mixin.safety;

import com.happysg.radar.block.behavior.networks.WeaponFiringControl;
import com.mrvew.firecontrol.block.BallisticComputerBlockEntity;
import com.mrvew.firecontrol.control.ComputerFireControl;
import com.mrvew.firecontrol.control.FireControlAccess;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import java.util.UUID;
import local.cmi.core.CombatServices;
import local.cmi.core.TargetId;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=ComputerFireControl.class, remap=false)
public abstract class TargetAuthorizationMixin {
    @Inject(method="resolveTarget", at=@At("RETURN"), cancellable=true, remap=false)
    private static void cmi$authorize(WeaponFiringControl wfc, FireControlAccess access, ComputerFireControl.State state,
            BallisticComputerBlockEntity computer, ServerLevel level, SubLevelAccess ownShip, long now,
            String battery, String gun, CallbackInfoReturnable<Object> result) {
        Object selected = result.getReturnValue();
        if (selected == null) return;
        if (wfc.cannonMount == null) { result.setReturnValue(null); return; }
        String key = ((ComputerTargetAccess)selected).cmi$key();
        TargetId.Kind kind;
        String id;
        if (key.startsWith("ent:")) { kind=TargetId.Kind.ENTITY; id=key.substring(4); }
        else if (key.startsWith("ship:")) { kind=TargetId.Kind.SABLE; id=key.substring(5); }
        else if (key.startsWith("missile:")) { kind=TargetId.Kind.SABLE; id=key.substring(8); }
        else { result.setReturnValue(null); return; }
        try {
            var contact = CombatServices.resolve(level, new TargetId(kind, UUID.fromString(id)));
            var actor = CombatServices.actor(level, wfc.cannonMount.getBlockPos());
            if (contact.isEmpty() || !CombatServices.canEngage(level, actor, contact.get())) result.setReturnValue(null);
        } catch (IllegalArgumentException e) { result.setReturnValue(null); }
    }
}
