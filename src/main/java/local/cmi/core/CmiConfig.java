package local.cmi.core;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-authoritative experimental integrations. New destructive integrations default off. */
public final class CmiConfig {
    private CmiConfig() {}

    public static final ModConfigSpec SERVER_SPEC;
    private static final ModConfigSpec.BooleanValue PROJECT_EXPLOSIVE_CONVENTIONAL_INTERCEPTION;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("experimental");
        PROJECT_EXPLOSIVE_CONVENTIONAL_INTERCEPTION = builder
                .comment("Allow managed CIWS shots to physically intercept only Project: Explosive Mk 82/Mk 84 bombs. Requires verified owner, current radar track, IFF and pre-detonation state. Default: false.")
                .define("projectExplosiveConventionalBombInterception", false);
        builder.pop();
        SERVER_SPEC = builder.build();
    }

    /** The opt-in is inert when the optional mod is absent. */
    public static boolean projectExplosiveConventionalInterceptionEnabled() {
        return PROJECT_EXPLOSIVE_CONVENTIONAL_INTERCEPTION.get()
                && ModList.get().isLoaded(ProjectExplosiveCompatibility.MOD_ID);
    }
}
