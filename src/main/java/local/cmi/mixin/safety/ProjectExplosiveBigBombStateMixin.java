package local.cmi.mixin.safety;

import local.cmi.core.ProjectExplosiveBombState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Optional exact-version bridge to Mk 84 impact and detonation state. */
@Pseudo
@Mixin(targets = "lil1llilol11.lo1i1iloioil.llooi0ilol1o11oiliioo1ll", remap = false)
public interface ProjectExplosiveBigBombStateMixin extends ProjectExplosiveBombState {
    @Override
    @Invoker("ll1ol00o111oioool0l011lo")
    boolean cmi$detonationStarted();

    @Override
    @Accessor("lii11l1iil010100loiio0li")
    boolean cmi$impactPending();
}
