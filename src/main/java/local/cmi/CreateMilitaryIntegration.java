package local.cmi;
import com.mojang.brigadier.arguments.*;
import java.util.UUID;
import java.util.List;
import local.cmi.core.*;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod(CreateMilitaryIntegration.ID)
public final class CreateMilitaryIntegration {
    public static final String ID="create_military_integration";
    public CreateMilitaryIntegration(){NeoForge.EVENT_BUS.addListener(this::commands);NeoForge.EVENT_BUS.addListener(this::stopped);NeoForge.EVENT_BUS.addListener(this::spawned);NeoForge.EVENT_BUS.addListener(this::explosion);}
    private void explosion(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event){
        if(!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level))return;
        var source=event.getExplosion().getDirectSourceEntity();if(source==null||!CombatServices.projectile(source)||!(source.getPersistentData().getBoolean("cmiManagedShot")||source.getPersistentData().getBoolean("cmiManaged")))return;
        if(!source.getPersistentData().hasUUID("cmiOwner")){event.getAffectedEntities().clear();event.getAffectedBlocks().clear();return;}
        var actor=CombatServices.actor(level,source);
        for(var target:java.util.List.copyOf(event.getAffectedEntities())){
            if(target==source||!CombatServices.projectile(target))continue;
            var contact=CombatServices.resolve(level,new TargetId(TargetId.Kind.ENTITY,target.getUUID()));
            if(contact.isPresent()&&CombatServices.canEngage(level,actor,contact.get())&&net.minecraft.world.level.Explosion.getSeenPercent(event.getExplosion().center(),target)>0&&CombatServices.neutralize(level,actor,contact.get())) event.getAffectedEntities().remove(target);
        }
        event.getAffectedEntities().removeIf(target->{var contact=CombatServices.resolve(level,new TargetId(TargetId.Kind.ENTITY,target.getUUID()));return contact.isEmpty()||CombatServices.relation(level,actor,contact.get())!=Relation.ENEMY;});
        event.getAffectedBlocks().removeIf(pos->CombatServices.isProtected(level,actor,net.minecraft.world.phys.Vec3.atCenterOf(pos)));
    }
    private void spawned(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event){
        var actor=ShotContext.current();if(!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel)||actor==null||actor.owner()==null||!CombatServices.projectile(event.getEntity()))return;
        var t=event.getEntity().getPersistentData();if(ShotContext.managed())t.putBoolean("cmiManagedShot",true);t.putUUID("cmiOwner",actor.owner());t.putString("cmiIff",actor.iff()==null?"":actor.iff());if(actor.platform()!=null)t.putUUID("cmiPlatform",actor.platform());
    }
    private void stopped(ServerStoppedEvent event){CombatServices.clear();local.cmi.weapons.CiwsCoordinator.clear();}
    private static List<? extends SubLevelAccess> loadedShips(ServerLevel level){
        var container=SubLevelContainer.getContainer(level);
        if(container==null)return List.of();
        return container.getAllSubLevels().stream().filter(s->!s.isRemoved()).toList();
    }
    private void commands(RegisterCommandsEvent e){
        e.getDispatcher().register(Commands.literal("cmi").requires(s->s.hasPermission(2))
            .then(Commands.literal("claim").then(Commands.argument("pos",BlockPosArgument.blockPos()).executes(ctx->{
                var source=ctx.getSource();var player=source.getPlayerOrException();var l=source.getLevel();var p=BlockPosArgument.getLoadedBlockPos(ctx,"pos");var be=l.getBlockEntity(p);
                if(be==null)throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("No block entity at position")).create();
                var a=CombatServices.actor(l,p);a=new Actor(player.getUUID(),a.iff(),a.platform());CmiData.get(l).claim(p,a);be.getPersistentData().putUUID("cmiOwner",player.getUUID());be.setChanged();source.sendSuccess(()->Component.literal("CMI ownership recorded"),false);return 1;
            })))
            .then(Commands.literal("ships").executes(ctx->{
                var source=ctx.getSource();var level=source.getLevel();var ships=loadedShips(level);var data=CmiData.get(level);
                source.sendSuccess(()->Component.literal("Loaded Sable ships: "+ships.size()),false);
                for(var ship:ships){var owner=data.ship(ship.getUniqueId());String ownerText=owner==null||owner.owner()==null?"unowned":owner.owner().toString();String name=String.valueOf(ship.getName());source.sendSuccess(()->Component.literal(ship.getUniqueId()+" | "+name+" | owner: "+ownerText),false);}
                return ships.size();
            }))
            .then(Commands.literal("shipowner").then(Commands.argument("shipUuid",UuidArgument.uuid()).then(Commands.argument("ownerUuid",UuidArgument.uuid()).executes(ctx->{
                var source=ctx.getSource();var level=source.getLevel();UUID shipId=UuidArgument.getUuid(ctx,"shipUuid"),ownerId=UuidArgument.getUuid(ctx,"ownerUuid");
                var loaded=loadedShips(level);var ship=loaded.stream().filter(s->s.getUniqueId().equals(shipId)).findFirst().orElse(null);
                if(ship==null){source.sendFailure(Component.literal("Sable UUID is not currently loaded: "+shipId));return 0;}
                if(!ShipOwnership.assignLoaded(CmiData.get(level),loaded.stream().map(SubLevelAccess::getUniqueId).toList(),shipId,ownerId)){source.sendFailure(Component.literal("Could not assign CMI ownership to loaded Sable."));return 0;}
                String name=String.valueOf(ship.getName());source.sendSuccess(()->Component.literal("CMI owner set: ship "+shipId+" ("+name+") -> "+ownerId),false);return 1;
            }))))
            .then(Commands.literal("relation").then(Commands.argument("shipUuid",UuidArgument.uuid()).executes(ctx->{
                var source=ctx.getSource();var level=source.getLevel();UUID shipId=UuidArgument.getUuid(ctx,"shipUuid");
                var contact=CombatServices.resolve(level,new TargetId(TargetId.Kind.SABLE,shipId));
                if(contact.isEmpty()){source.sendFailure(Component.literal("Sable UUID is not currently loaded: "+shipId));return 0;}
                var player=source.getPlayerOrException();var actor=CombatServices.actor(level,player);var owner=CmiData.get(level).ship(shipId);
                String ownerText=owner==null||owner.owner()==null?"unowned":owner.owner().toString();Relation relation=CombatServices.relation(level,actor,contact.get());
                source.sendSuccess(()->Component.literal("Sable "+shipId+" | owner: "+ownerText+" | platform: "+String.valueOf(contact.get().platform())+" | relation to you: "+relation),false);return 1;
            })))
            .then(Commands.literal("hostile").then(Commands.argument("player",EntityArgument.player()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(ctx->{
                var s=ctx.getSource();CmiData.get(s.getLevel()).hostile(s.getPlayerOrException().getUUID(),EntityArgument.getPlayer(ctx,"player").getUUID(),BoolArgumentType.getBool(ctx,"enabled"));s.sendSuccess(()->Component.literal("CMI hostility updated (allied relations still prevail)"),false);return 1;
            }))))
            .then(Commands.literal("hostileuuid").then(Commands.argument("ownerUuid",UuidArgument.uuid()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(ctx->{
                var s=ctx.getSource();UUID target=UuidArgument.getUuid(ctx,"ownerUuid"),issuer=s.getPlayerOrException().getUUID();boolean enabled=BoolArgumentType.getBool(ctx,"enabled");
                CmiData.get(s.getLevel()).hostile(issuer,target,enabled);s.sendSuccess(()->Component.literal("CMI hostility for owner "+target+" set to "+enabled+" (FTB/IFF allies still prevail)"),false);return 1;
            }))))
            .then(Commands.literal("heat").then(Commands.argument("ship",UuidArgument.uuid()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(ctx->{var s=ctx.getSource();CmiData.get(s.getLevel()).hot(UuidArgument.getUuid(ctx,"ship"),BoolArgumentType.getBool(ctx,"enabled"));s.sendSuccess(()->Component.literal("CMI explicit thermal signature updated"),false);return 1;}))))
            .then(Commands.literal("interceptor").then(Commands.argument("pos",BlockPosArgument.blockPos()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(ctx->{
                var s=ctx.getSource();var p=BlockPosArgument.getLoadedBlockPos(ctx,"pos");var be=s.getLevel().getBlockEntity(p);
                if(!(be instanceof riftyboi.cbcmodernwarfare.munitions.munitions_contraption_launcher.guidance.infrared_homing.InfraredSeekerGuidanceBlockEntity)&&!(be instanceof rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity)&&!(be instanceof riftyboi.cbcmodernwarfare.cannon_control.compact_mount.CompactCannonMountBlockEntity))return 0;
                be.getPersistentData().putBoolean("cmiInterceptor",BoolArgumentType.getBool(ctx,"enabled"));be.getPersistentData().putUUID("cmiOwner",s.getPlayerOrException().getUUID());be.setChanged();s.sendSuccess(()->Component.literal("CMI interceptor mode set on seeker; preserve its NBT when loading"),false);return 1;
            }))))
            .then(Commands.literal("ciwsradar").then(Commands.argument("mount",BlockPosArgument.blockPos()).then(Commands.argument("radar",BlockPosArgument.blockPos()).executes(ctx->{
                var s=ctx.getSource();var mount=BlockPosArgument.getLoadedBlockPos(ctx,"mount");var radar=BlockPosArgument.getLoadedBlockPos(ctx,"radar");
                String error=local.cmi.weapons.CiwsCoordinator.bindRadar(s.getLevel(),mount,radar,s.getPlayerOrException().getUUID());
                if(error!=null){s.sendFailure(Component.literal("CIWS radar link rejected: "+error));return 0;}
                s.sendSuccess(()->Component.literal("CIWS linked to physical radar "+radar+" at mount "+mount),false);return 1;
            }))))
            .then(Commands.literal("ciws").then(Commands.argument("mount",BlockPosArgument.blockPos()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(ctx->{
                var s=ctx.getSource();var level=s.getLevel();var pos=BlockPosArgument.getLoadedBlockPos(ctx,"mount");var be=level.getBlockEntity(pos);var player=s.getPlayerOrException();boolean enabled=BoolArgumentType.getBool(ctx,"enabled");
                if(!(be instanceof riftyboi.cbcmodernwarfare.cannon_control.compact_mount.CompactCannonMountBlockEntity mount)){s.sendFailure(Component.literal("Position is not a compact cannon mount."));return 0;}
                var actor=CombatServices.actor(level,pos);
                if(actor.owner()==null||!actor.owner().equals(player.getUUID())||!be.getPersistentData().hasUUID("cmiOwner")||!be.getPersistentData().getUUID("cmiOwner").equals(player.getUUID())){s.sendFailure(Component.literal("Claim this mount with /cmi claim first."));return 0;}
                if(enabled){String error=local.cmi.weapons.CiwsCoordinator.enableError(level,pos,player.getUUID());if(error!=null){s.sendFailure(Component.literal("CIWS activation rejected: "+error));return 0;}}
                local.cmi.weapons.CiwsCoordinator.setEnabled(level,mount,enabled);
                s.sendSuccess(()->Component.literal("CIWS "+(enabled?"enabled":"disabled")+" at "+pos),false);return 1;
            }))))
            .then(Commands.literal("radarspearprofile").then(Commands.argument("pos",BlockPosArgument.blockPos()).then(Commands.argument("profile",StringArgumentType.word()).executes(ctx->{
                var s=ctx.getSource();var p=BlockPosArgument.getLoadedBlockPos(ctx,"pos");var be=s.getLevel().getBlockEntity(p);
                if(!(be instanceof com.wariumce.block.entity.RadarSpearMissileHardpointBlockEntity))return 0;
                String profile=StringArgumentType.getString(ctx,"profile");
                if(!"direct_sable".equals(profile)&&!"explosive_sable".equals(profile))return 0;
                var player=s.getPlayerOrException();var current=CombatServices.actor(s.getLevel(),p);
                if(current.owner()!=null&&!current.owner().equals(player.getUUID())){s.sendFailure(Component.literal("Reclaim this hardpoint with /cmi claim first."));return 0;}
                var data=be.getPersistentData();data.putBoolean("cmiManaged",true);data.putUUID("cmiOwner",player.getUUID());data.putString("cmiRadarProfile",profile);be.setChanged();
                s.sendSuccess(()->Component.literal("Radar Spear profile set to "+profile+". explosive_sable is a CMI-only, block-structural area effect; Warium's fragments and native explosions stay disabled for this managed launch."),false);return 1;
            }))))
            .then(Commands.literal("radarspearradar").then(Commands.argument("hardpoint",BlockPosArgument.blockPos()).then(Commands.argument("radar",BlockPosArgument.blockPos()).executes(ctx->{
                var s=ctx.getSource();var hardpoint=BlockPosArgument.getLoadedBlockPos(ctx,"hardpoint");var radar=BlockPosArgument.getLoadedBlockPos(ctx,"radar");
                var player=s.getPlayerOrException();String error=local.cmi.missiles.RadarSpearAutomation.bindRadar(s.getLevel(),hardpoint,radar,player.getUUID());
                if(error!=null){s.sendFailure(Component.literal("Radar Spear radar link rejected: "+error));return 0;}
                s.sendSuccess(()->Component.literal("Linked physical Create: Radars block at "+radar+" to Radar Spear hardpoint at "+hardpoint+" in "+s.getLevel().dimension().location()),false);return 1;
            }))))
            .then(Commands.literal("radarspearauto").then(Commands.argument("pos",BlockPosArgument.blockPos()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(ctx->{
                var s=ctx.getSource();var pos=BlockPosArgument.getLoadedBlockPos(ctx,"pos");var be=s.getLevel().getBlockEntity(pos);var player=s.getPlayerOrException();boolean enabled=BoolArgumentType.getBool(ctx,"enabled");
                if(be==null){s.sendFailure(Component.literal("No loaded block entity at "+pos));return 0;}
                var owner=CombatServices.actor(s.getLevel(),pos);
                if(owner.owner()==null||!owner.owner().equals(player.getUUID())){s.sendFailure(Component.literal("The hardpoint must have this player's CMI ownership."));return 0;}
                if(enabled){String error=local.cmi.missiles.RadarSpearAutomation.enableError(s.getLevel(),pos,player.getUUID());if(error!=null){s.sendFailure(Component.literal("Radar Spear auto rejected: "+error));return 0;}}
                else if(!(be instanceof com.wariumce.block.entity.RadarSpearMissileHardpointBlockEntity)&&!(be instanceof com.wariumce.block.entity.EmptyMissileHardpointBlockEntity)){s.sendFailure(Component.literal("Position is not a Radar Spear hardpoint or its empty replacement."));return 0;}
                local.cmi.missiles.RadarSpearAutomation.setEnabled(be,enabled);
                s.sendSuccess(()->Component.literal("Radar Spear automatic mode "+(enabled?"enabled":"disabled")+" at "+pos+". Radar track must pass current CMI IFF before Warium is asked to fire."),false);return 1;
            }))))
            .then(Commands.literal("managed").then(Commands.argument("pos",BlockPosArgument.blockPos()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(ctx->{
                var s=ctx.getSource();var p=BlockPosArgument.getLoadedBlockPos(ctx,"pos");var be=s.getLevel().getBlockEntity(p);if(be==null)return 0;
                be.getPersistentData().putBoolean("cmiManaged",BoolArgumentType.getBool(ctx,"enabled"));be.getPersistentData().putUUID("cmiOwner",s.getPlayerOrException().getUUID());be.setChanged();s.sendSuccess(()->Component.literal("CMI managed launcher flag updated; native Radar Spear coordinate contract remains required"),false);return 1;
            })))));
    }
}
