package local.cmi.mixin.safety;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets="com.mrvew.firecontrol.control.ComputerFireControl$Target", remap=false)
public interface ComputerTargetAccess {
    @Accessor("key") String cmi$key();
}
