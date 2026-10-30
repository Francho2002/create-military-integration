package local.cmi;
import com.mojang.brigadier.arguments.*;
import java.util.UUID;
import local.cmi.core.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
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
    private void stopped(ServerStoppedEvent event){CombatServices.clear();}
    private void commands(RegisterCommandsEvent e){
        e.getDispatcher().register(Commands.literal("cmi").requires(s->s.hasPermission(2))
            .then(Commands.literal("claim").then(Commands.argument("pos",BlockPosArgument.blockPos()).executes(ctx->{
                var source=ctx.getSource();var player=source.getPlayerOrException();var l=source.getLevel();var p=BlockPosArgument.getLoadedBlockPos(ctx,"pos");var be=l.getBlockEntity(p);
                if(be==null)throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("No block entity at position")).create();
                var a=CombatServices.actor(l,p);a=new Actor(player.getUUID(),a.iff(),a.platform());CmiData.get(l).claim(p,a);be.getPersistentData().putUUID("cmiOwner",player.getUUID());be.setChanged();source.sendSuccess(()->Component.literal("CMI ownership recorded"),false);return 1;
            })))
            .then(Commands.literal("hostile").then(Commands.argument("player",EntityArgument.player()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(ctx->{
                var s=ctx.getSource();CmiData.get(s.getLevel()).hostile(s.getPlayerOrException().getUUID(),EntityArgument.getPlayer(ctx,"player").getUUID(),BoolArgumentType.getBool(ctx,"enabled"));s.sendSuccess(()->Component.literal("CMI hostility updated (allied relations still prevail)"),false);return 1;
            }))))
            .then(Commands.literal("heat").then(Commands.argument("ship",UuidArgument.uuid()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(ctx->{var s=ctx.getSource();CmiData.get(s.getLevel()).hot(UuidArgument.getUuid(ctx,"ship"),BoolArgumentType.getBool(ctx,"enabled"));s.sendSuccess(()->Component.literal("CMI explicit thermal signature updated"),false);return 1;}))))
            .then(Commands.literal("interceptor").then(Commands.argument("pos",BlockPosArgument.blockPos()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(ctx->{
                var s=ctx.getSource();var p=BlockPosArgument.getLoadedBlockPos(ctx,"pos");var be=s.getLevel().getBlockEntity(p);
                if(!(be instanceof riftyboi.cbcmodernwarfare.munitions.munitions_contraption_launcher.guidance.infrared_homing.InfraredSeekerGuidanceBlockEntity)&&!(be instanceof rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity)&&!(be instanceof riftyboi.cbcmodernwarfare.cannon_control.compact_mount.CompactCannonMountBlockEntity))return 0;
                be.getPersistentData().putBoolean("cmiInterceptor",BoolArgumentType.getBool(ctx,"enabled"));be.getPersistentData().putUUID("cmiOwner",s.getPlayerOrException().getUUID());be.setChanged();s.sendSuccess(()->Component.literal("CMI interceptor mode set on seeker; preserve its NBT when loading"),false);return 1;
            }))))
            .then(Commands.literal("managed").then(Commands.argument("pos",BlockPosArgument.blockPos()).then(Commands.argument("enabled",BoolArgumentType.bool()).executes(ctx->{
                var s=ctx.getSource();var p=BlockPosArgument.getLoadedBlockPos(ctx,"pos");var be=s.getLevel().getBlockEntity(p);if(be==null)return 0;
                be.getPersistentData().putBoolean("cmiManaged",BoolArgumentType.getBool(ctx,"enabled"));be.getPersistentData().putUUID("cmiOwner",s.getPlayerOrException().getUUID());be.setChanged();s.sendSuccess(()->Component.literal("CMI managed launcher flag updated; native Radar Spear coordinate contract remains required"),false);return 1;
            })))));
    }
}
