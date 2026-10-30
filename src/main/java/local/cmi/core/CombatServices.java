package local.cmi.core;
import java.util.*;
import com.mrvew.firecontrol.iff.*;
import com.mrvew.firecontrol.ship.Ships;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.*;
/** Server-thread shared contact snapshots; references are resolved afresh for every engagement. */
public final class CombatServices {
    private CombatServices(){}
    private record Snapshot(long tick,List<MilitaryContact> contacts){}
    private record Reservation(UUID interceptor,long until){}
    private static final Map<ServerLevel,Snapshot> CACHE=new WeakHashMap<>();
    private static final Map<ServerLevel,Map<TargetId,Reservation>> RESERVED=new WeakHashMap<>();
    private static void server(ServerLevel l){if(!l.getServer().isSameThread())throw new IllegalStateException("CMI world access off server thread");}
    public static Actor actor(ServerLevel level,BlockPos pos){
        server(level);var claimed=CmiData.get(level).claim(pos);if(claimed!=null)return claimed;
        var be=level.getBlockEntity(pos);if(be!=null&&be.getPersistentData().hasUUID("cmiOwner")){var t=be.getPersistentData();var a=new Actor(t.getUUID("cmiOwner"),t.getString("cmiIff"),platform(level,pos));CmiData.get(level).bindShip(a.platform(),a);return a;}
        if(be instanceof com.mrvew.firecontrol.block.BallisticComputerBlockEntity computer)return new Actor(computer.owner(),computer.iffCode(),platform(level,pos));
        UUID ship=platform(level,pos);var owner=CmiData.get(level).ship(ship);return owner==null?new Actor(null,"",ship):owner;
    }
    private static UUID platform(ServerLevel level,BlockPos p){var s=Ships.containing(level,p);return s==null?null:s.getUniqueId();}
    public static Actor actor(ServerLevel level,Entity e){
        server(level);var tag=e.getPersistentData();if(tag.hasUUID("cmiOwner"))return new Actor(tag.getUUID("cmiOwner"),tag.getString("cmiIff"),tag.hasUUID("cmiPlatform")?tag.getUUID("cmiPlatform"):null);
        if(e instanceof Projectile p && p.getOwner()!=null)return actor(level,p.getOwner());
        if(e instanceof rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity cannon && cannon.getController() instanceof net.minecraft.world.level.block.entity.BlockEntity be)return actor(level,be.getBlockPos());
        if(e instanceof net.minecraft.world.entity.player.Player)return new Actor(e.getUUID(),"",null);
        var s=Sable.HELPER.getTrackingOrVehicleSubLevel(e);UUID id=s==null?null:s.getUniqueId();var owner=CmiData.get(level).ship(id);return owner==null?new Actor(null,"",id):owner;
    }
    public static boolean projectile(Entity e){
        if(e instanceof rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile||e instanceof riftyboi.cbcmodernwarfare.munitions.contraptions.MunitionsPhysicsContraptionEntity)return true;
        if(!(e instanceof Projectile))return false;
        String type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        return Set.of("wariumce:fire_spear_rocket_projectile","wariumce:radar_spear_missile_projectile","wariumce:seeker_spear_missile_projectile","wariumce:large_rocket","wariumce:cluster_rocket","wariumce:ir_missile").contains(type);
    }
    private static MilitaryContact entity(ServerLevel l,Entity e){var a=actor(l,e);return new MilitaryContact(new TargetId(TargetId.Kind.ENTITY,e.getUUID()),e.getBoundingBox().getCenter(),e.getDeltaMovement(),e.getBoundingBox(),a.owner(),a.platform(),projectile(e),e instanceof LivingEntity&&!e.fireImmune(),l.getGameTime());}
    private static MilitaryContact ship(ServerLevel l,SubLevelAccess s){var b=s.boundingBox();var a=CmiData.get(l).ship(s.getUniqueId());return new MilitaryContact(new TargetId(TargetId.Kind.SABLE,s.getUniqueId()),Ships.boundsCenter(s),Ships.velocityAt(l,s,Ships.boundsCenter(s)),new AABB(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()),a==null?null:a.owner(),s.getUniqueId(),false,CmiData.get(l).hot(s.getUniqueId()),l.getGameTime());}
    public static List<MilitaryContact> contacts(ServerLevel l){server(l);var old=CACHE.get(l);if(old!=null&&l.getGameTime()-old.tick()<10)return old.contacts();var out=new ArrayList<MilitaryContact>();int budget=4096;for(var e:l.getAllEntities()){if(--budget<0)break;if(e.isAlive()&&(e instanceof LivingEntity||projectile(e)||e instanceof com.simibubi.create.content.contraptions.AbstractContraptionEntity))out.add(entity(l,e));}var c=SubLevelContainer.getContainer(l);if(c!=null)for(var s:c.getAllSubLevels()){if(!s.isRemoved())out.add(ship(l,s));}var list=List.copyOf(out);CACHE.put(l,new Snapshot(l.getGameTime(),list));return list;}
    public static Optional<MilitaryContact> resolve(ServerLevel l,TargetId id){server(l);if(id.kind()==TargetId.Kind.ENTITY){var e=l.getEntity(id.uuid());return e==null||!e.isAlive()?Optional.empty():Optional.of(entity(l,e));}var c=SubLevelContainer.getContainer(l);var s=c==null?null:c.getSubLevel(id.uuid());return s==null||s.isRemoved()?Optional.empty():Optional.of(ship(l,s));}
    public static Relation relation(ServerLevel l,Actor a,MilitaryContact c){server(l);if(a.owner()==null)return Relation.UNKNOWN;if(c.id().kind()==TargetId.Kind.ENTITY){var e=l.getEntity(c.id().uuid());if(e!=null&&net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace().equals("minecolonies"))return Relation.FRIEND;}if(a.platform()!=null&&a.platform().equals(c.platform()))return Relation.FRIEND;if(a.owner().equals(c.owner()))return Relation.FRIEND;
        var allegiance=Iff.Allegiance.of(l,a.owner(),"",List.of(),a.iff()==null?"":a.iff());
        if(c.id().kind()==TargetId.Kind.SABLE){var container=SubLevelContainer.getContainer(l);var s=container==null?null:container.getSubLevel(c.id().uuid());if(s!=null&&Iff.ship(l,allegiance,c.id().uuid(),s.getName(),com.mrvew.firecontrol.target.Sensor.crews(l).getOrDefault(c.id().uuid(),List.of()))==Iff.Side.FRIEND)return Relation.FRIEND;}
        if(c.owner()!=null){if(Iff.playerId(l,allegiance,c.owner())==Iff.Side.FRIEND)return Relation.FRIEND;return CmiData.get(l).enemy(a.owner(),c.owner())?Relation.ENEMY:Relation.NEUTRAL;}
        if(c.id().kind()==TargetId.Kind.ENTITY&&l.getEntity(c.id().uuid()) instanceof Enemy)return Relation.ENEMY;return Relation.UNKNOWN;
    }
    public static boolean canEngage(ServerLevel l,Actor a,MilitaryContact c){var current=resolve(l,c.id());return current.isPresent()&&relation(l,a,current.get())==Relation.ENEMY&&!isProtected(l,a,current.get().position());}
    public static boolean isProtected(ServerLevel l,Actor a,Vec3 p){server(l);if(!l.hasChunkAt(BlockPos.containing(p)))return true;
        if(net.neoforged.fml.ModList.get().isLoaded("minecolonies")&&com.minecolonies.api.colony.IColonyManager.getInstance().isCoordinateInAnyColony(l,BlockPos.containing(p)))return true;
        var side=Iff.claim(l,Iff.Allegiance.of(l,a.owner(),"",List.of(),a.iff()==null?"":a.iff()),new net.minecraft.world.level.ChunkPos(BlockPos.containing(p)));return side==Iff.Side.FRIEND;}
    public static boolean reserve(ServerLevel l,TargetId threat,UUID interceptor,int ttl){server(l);var map=RESERVED.computeIfAbsent(l,k->new HashMap<>());map.values().removeIf(v->v.until()<l.getGameTime());var r=map.get(threat);if(r!=null&&!r.interceptor().equals(interceptor))return false;map.put(threat,new Reservation(interceptor,l.getGameTime()+Math.max(1,ttl)));return true;}
    public static void release(ServerLevel l,TargetId threat,UUID interceptor){server(l);var m=RESERVED.get(l);if(m!=null){var r=m.get(threat);if(r!=null&&r.interceptor().equals(interceptor))m.remove(threat);}}
    public static boolean neutralize(ServerLevel l,Actor actor,MilitaryContact contact){server(l);if(!contact.projectile()||!canEngage(l,actor,contact)||contact.id().kind()!=TargetId.Kind.ENTITY)return false;var e=l.getEntity(contact.id().uuid());if(e==null||!projectile(e))return false;e.discard();return true;}
    public static void clear(){CACHE.clear();RESERVED.clear();}
}
