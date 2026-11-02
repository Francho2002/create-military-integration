package local.cmi;

import com.mojang.brigadier.CommandDispatcher;
import java.util.UUID;
import local.cmi.core.Actor;
import local.cmi.core.MountedBlockReference;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Explicit Sable-plot locations and non-executable onboard radar bindings. */
public final class MountedSystemCommands {
    private MountedSystemCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("cmi").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("mounted").then(Commands.literal("inspect")
                .then(Commands.literal("world").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> inspect(context.getSource(), MountedBlockReference.world(
                                context.getSource().getLevel().dimension().location(), BlockPosArgument.getLoadedBlockPos(context, "pos"))))))
                .then(Commands.literal("sable").then(Commands.argument("shipUuid", UuidArgument.uuid())
                        .then(Commands.argument("localPos", BlockPosArgument.blockPos())
                                .executes(context -> inspect(context.getSource(), MountedBlockReference.sable(
                                        context.getSource().getLevel().dimension().location(), UuidArgument.getUuid(context, "shipUuid"),
                                        BlockPosArgument.getBlockPos(context, "localPos")))))))));

        root.then(Commands.literal("ciwsradar").then(Commands.literal("sable")
                .then(Commands.argument("mountShip", UuidArgument.uuid()).then(Commands.argument("mountLocalPos", BlockPosArgument.blockPos())
                        .then(Commands.argument("radarShip", UuidArgument.uuid()).then(Commands.argument("radarLocalPos", BlockPosArgument.blockPos())
                                .executes(context -> bindCiws(context.getSource(),
                                        UuidArgument.getUuid(context, "mountShip"), BlockPosArgument.getBlockPos(context, "mountLocalPos"),
                                        UuidArgument.getUuid(context, "radarShip"), BlockPosArgument.getBlockPos(context, "radarLocalPos")))))))));

        root.then(Commands.literal("radarspearradar").then(Commands.literal("sable")
                .then(Commands.argument("hardpointShip", UuidArgument.uuid()).then(Commands.argument("hardpointLocalPos", BlockPosArgument.blockPos())
                        .then(Commands.argument("radarShip", UuidArgument.uuid()).then(Commands.argument("radarLocalPos", BlockPosArgument.blockPos())
                                .executes(context -> bindRadarSpear(context.getSource(),
                                        UuidArgument.getUuid(context, "hardpointShip"), BlockPosArgument.getBlockPos(context, "hardpointLocalPos"),
                                        UuidArgument.getUuid(context, "radarShip"), BlockPosArgument.getBlockPos(context, "radarLocalPos")))))))));

        root.then(Commands.literal("radarspearprofile").then(Commands.literal("sable")
                .then(Commands.argument("shipUuid", UuidArgument.uuid()).then(Commands.argument("localPos", BlockPosArgument.blockPos())
                        .then(Commands.argument("profile", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(context -> setSableRadarSpearProfile(context.getSource(), UuidArgument.getUuid(context, "shipUuid"),
                                        BlockPosArgument.getBlockPos(context, "localPos"),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "profile"))))))));

        root.then(Commands.literal("ciws").then(Commands.literal("sable")
                .then(Commands.argument("shipUuid", UuidArgument.uuid()).then(Commands.argument("localPos", BlockPosArgument.blockPos())
                        .then(Commands.argument("enabled", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                .executes(context -> setCiws(context.getSource(), UuidArgument.getUuid(context, "shipUuid"),
                                        BlockPosArgument.getBlockPos(context, "localPos"),
                                        com.mojang.brigadier.arguments.BoolArgumentType.getBool(context, "enabled"))))))));

        root.then(Commands.literal("radarspearauto").then(Commands.literal("sable")
                .then(Commands.argument("shipUuid", UuidArgument.uuid()).then(Commands.argument("localPos", BlockPosArgument.blockPos())
                        .then(Commands.argument("enabled", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                .executes(context -> setRadarSpearAuto(context.getSource(), UuidArgument.getUuid(context, "shipUuid"),
                                        BlockPosArgument.getBlockPos(context, "localPos"),
                                        com.mojang.brigadier.arguments.BoolArgumentType.getBool(context, "enabled"))))))));

        dispatcher.register(root);
    }

    private static int inspect(CommandSourceStack source, MountedBlockReference reference) {
        var resolved = reference.resolve(source.getLevel()).orElse(null);
        if (resolved == null) return fail(source, "No loaded block entity resolves at this WORLD/SABLE reference.");
        Actor actor = reference.actor(source.getLevel());
        String owner = actor.owner() == null ? "unknown" : actor.owner().toString();
        String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(resolved.blockEntity().getBlockState().getBlock()).toString();
        String location = reference.space() == MountedBlockReference.Space.SABLE
                ? "SABLE ship=" + reference.shipUuid() + " local=" + reference.position() + " stored=" + resolved.storedPosition()
                : "WORLD " + reference.dimension() + " pos=" + reference.position();
        source.sendSuccess(() -> Component.literal(location + " | block=" + block + " | CMI owner=" + owner +
                " | current world center=" + resolved.worldCenter()), false);
        return 1;
    }

    private static int bindCiws(CommandSourceStack source, UUID mountShip, net.minecraft.core.BlockPos mountPos,
                               UUID radarShip, net.minecraft.core.BlockPos radarPos) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = source.getLevel();
        String error = local.cmi.weapons.CiwsCoordinator.bindSableRadar(level, mountShip, mountPos, radarShip, radarPos,
                source.getPlayerOrException().getUUID());
        if (error != null) return fail(source, "CIWS Sable binding rejected: " + error);
        source.sendSuccess(() -> Component.literal("CIWS Sable radar reference saved. Binding is non-executable; onboard radar tick, aim, muzzle and fire remain BLOQUEADOS."), false);
        return 1;
    }

    private static int bindRadarSpear(CommandSourceStack source, UUID hardpointShip, net.minecraft.core.BlockPos hardpointPos,
                                      UUID radarShip, net.minecraft.core.BlockPos radarPos) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = source.getLevel();
        String error = local.cmi.missiles.RadarSpearAutomation.bindSableRadar(level, hardpointShip, hardpointPos, radarShip, radarPos,
                source.getPlayerOrException().getUUID());
        if (error != null) return fail(source, "Radar Spear Sable binding rejected: " + error);
        source.sendSuccess(() -> Component.literal("Radar Spear Sable radar reference saved. Binding is non-executable; radar auto and native Warium spawn remain BLOQUEADOS."), false);
        return 1;
    }

    private static int setCiws(CommandSourceStack source, UUID shipUuid, net.minecraft.core.BlockPos localPos, boolean enabled)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = source.getLevel();
        MountedBlockReference reference = MountedBlockReference.sable(level.dimension().location(), shipUuid, localPos);
        BlockEntity blockEntity = reference.resolve(level).map(MountedBlockReference.Resolved::blockEntity).orElse(null);
        if (!(blockEntity instanceof riftyboi.cbcmodernwarfare.cannon_control.compact_mount.CompactCannonMountBlockEntity mount))
            return fail(source, "No loaded compact mount resolves at this Sable ship/local position.");
        if (enabled) {
            local.cmi.weapons.CiwsCoordinator.disableNonExecutableMounted(mount);
            if (!ownedByCurrentPlayer(source, reference)) return fail(source, "BLOQUEADO: CIWS true was cleared from the Sable mount, but ship ownership is unknown or belongs to another player.");
            return fail(source, "BLOQUEADO: Sable 2.0.6 does not tick plot block entities, and no native onboard aim/muzzle/fireShot route is credited. CIWS mode was explicitly set false.");
        }
        if (!ownedByCurrentPlayer(source, reference)) return fail(source, "Sable ship ownership is unknown or belongs to another player; use /cmi shipowner.");
        local.cmi.weapons.CiwsCoordinator.disableNonExecutableMounted(mount);
        source.sendSuccess(() -> Component.literal("CIWS mode disabled on the Sable mount."), false);
        return 1;
    }

    private static int setRadarSpearAuto(CommandSourceStack source, UUID shipUuid, net.minecraft.core.BlockPos localPos, boolean enabled)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = source.getLevel();
        MountedBlockReference reference = MountedBlockReference.sable(level.dimension().location(), shipUuid, localPos);
        BlockEntity blockEntity = reference.resolve(level).map(MountedBlockReference.Resolved::blockEntity).orElse(null);
        if (!(blockEntity instanceof com.wariumce.block.entity.RadarSpearMissileHardpointBlockEntity) &&
                !(blockEntity instanceof com.wariumce.block.entity.EmptyMissileHardpointBlockEntity))
            return fail(source, "No loaded Radar Spear hardpoint resolves at this Sable ship/local position.");
        if (enabled) {
            local.cmi.missiles.RadarSpearAutomation.setEnabled(blockEntity, false);
            if (!ownedByCurrentPlayer(source, reference)) return fail(source, "BLOQUEADO: Radar Spear auto was cleared on the Sable hardpoint, but ship ownership is unknown or belongs to another player.");
            return fail(source, "BLOQUEADO: Sable 2.0.6 does not tick plot block entities; onboard radar tracks stay stale and the native Warium spawn requires ServerLevel. Auto mode was explicitly set false.");
        }
        if (!ownedByCurrentPlayer(source, reference)) return fail(source, "Sable ship ownership is unknown or belongs to another player; use /cmi shipowner.");
        local.cmi.missiles.RadarSpearAutomation.setEnabled(blockEntity, false);
        source.sendSuccess(() -> Component.literal("Radar Spear auto mode disabled on the Sable hardpoint."), false);
        return 1;
    }

    private static int setSableRadarSpearProfile(CommandSourceStack source, UUID shipUuid,
                                                 net.minecraft.core.BlockPos localPos, String profile)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!"direct_sable".equals(profile) && !"explosive_sable".equals(profile))
            return fail(source, "Sable Radar Spear profile must be direct_sable or explosive_sable.");
        ServerLevel level = source.getLevel();
        MountedBlockReference reference = MountedBlockReference.sable(level.dimension().location(), shipUuid, localPos);
        BlockEntity blockEntity = reference.resolve(level).map(MountedBlockReference.Resolved::blockEntity).orElse(null);
        if (!(blockEntity instanceof com.wariumce.block.entity.RadarSpearMissileHardpointBlockEntity))
            return fail(source, "No loaded Radar Spear hardpoint resolves at this Sable ship/local position.");
        local.cmi.missiles.RadarSpearAutomation.setEnabled(blockEntity, false);
        Actor actor = reference.actor(level);
        UUID player = source.getPlayerOrException().getUUID();
        if (actor.owner() == null || !actor.owner().equals(player))
            return fail(source, "Sable ship ownership is unknown, conflicting, or belongs to another player; use /cmi shipowner.");
        var data = blockEntity.getPersistentData();
        data.putBoolean("cmiManaged", true);
        data.putUUID("cmiOwner", actor.owner());
        data.putString("cmiRadarProfile", profile);
        blockEntity.setChanged();
        source.sendSuccess(() -> Component.literal("Radar Spear profile saved for the Sable hardpoint. Auto remains false; launch and native spawn remain BLOQUEADOS."), false);
        return 1;
    }

    private static boolean ownedByCurrentPlayer(CommandSourceStack source, MountedBlockReference reference) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID player = source.getPlayerOrException().getUUID();
        Actor actor = reference.actor(source.getLevel());
        return actor.owner() != null && actor.owner().equals(player);
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
