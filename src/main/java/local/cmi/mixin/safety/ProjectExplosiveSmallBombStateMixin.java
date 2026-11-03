package local.cmi.mixin.safety;

import local.cmi.core.ProjectExplosiveBombState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Optional exact-version bridge to Mk 82 impact and detonation state. */
@Pseudo
@Mixin(targets = "lil1llilol11.lo1i1iloioil.l0io0i1o00lll00olo0110ii", remap = false)
public interface ProjectExplosiveSmallBombStateMixin extends ProjectExplosiveBombState {
    @Override
    @Invoker("ll1ol00o111oioool0l011lo")
    boolean cmi$detonationStarted();

    @Override
    @Accessor("l11oo0i1illii0o0liol1o1i")
    boolean cmi$impactPending();
}
