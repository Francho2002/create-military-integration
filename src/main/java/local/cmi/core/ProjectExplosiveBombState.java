package local.cmi.core;

/** Implemented only by optional, exact-version Project: Explosive Mk 82/Mk 84 mixins. */
public interface ProjectExplosiveBombState {
    boolean cmi$detonationStarted();

    /** True once PE has accepted a terrain impact, even before HasExploded becomes true. */
    boolean cmi$impactPending();

    /** Conservative destruction gate covering accepted impact and active detonation. */
    default boolean cmi$impactOrDetonationStarted() {
        return cmi$impactPending() || cmi$detonationStarted();
    }
}
