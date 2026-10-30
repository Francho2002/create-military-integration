package local.cmi.core;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
/** Independent world data: no writes to third-party databases or mod configuration. */
public final class CmiData extends SavedData {
    private final Map<Long, Actor> claims = new HashMap<>();
    private final Map<UUID, Actor> ships = new HashMap<>();
    private final Set<UUID> hotShips = new HashSet<>();
    private final Set<String> enemies = new HashSet<>();
    public static CmiData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(CmiData::new, CmiData::load, null), "create_military_integration");
    }
    public Actor claim(BlockPos pos) { return claims.get(pos.asLong()); }
    public Map<Long, Actor> claims() { return Map.copyOf(claims); }
    public Actor ship(UUID id) { return id == null ? null : ships.get(id); }
    public void bindShip(UUID id,Actor actor){if(id!=null&&!actor.equals(ships.get(id))){ships.put(id,actor);setDirty();}}
    public boolean hot(UUID id) { return hotShips.contains(id); }
    public boolean enemy(UUID a, UUID b) { return a != null && b != null && enemies.contains(a + ":" + b); }
    public void claim(BlockPos pos, Actor actor) { claims.put(pos.asLong(), actor); if(actor.platform()!=null) ships.put(actor.platform(), actor); setDirty(); }
    public void hot(UUID id, boolean value) { if(value) hotShips.add(id); else hotShips.remove(id); setDirty(); }
    public void hostile(UUID a, UUID b, boolean value) { if(value) enemies.add(a+":"+b); else enemies.remove(a+":"+b); setDirty(); }
    private static CompoundTag actor(Actor a) { var t=new CompoundTag(); if(a.owner()!=null)t.putUUID("owner",a.owner()); t.putString("iff",a.iff()==null?"":a.iff()); if(a.platform()!=null)t.putUUID("platform",a.platform()); return t; }
    private static Actor actor(CompoundTag t) { return new Actor(t.hasUUID("owner")?t.getUUID("owner"):null,t.getString("iff"),t.hasUUID("platform")?t.getUUID("platform"):null); }
    public static CmiData load(CompoundTag tag, HolderLookup.Provider provider) {
        var d=new CmiData(); for(var v:tag.getList("claims",10)){var t=(CompoundTag)v;d.claims.put(t.getLong("pos"),actor(t));}
        for(var v:tag.getList("ships",10)){var t=(CompoundTag)v;d.ships.put(t.getUUID("id"),actor(t));}
        for(var v:tag.getList("hot",8)) { try{d.hotShips.add(UUID.fromString(v.getAsString()));}catch(IllegalArgumentException ignored){} }
        for(var v:tag.getList("enemies",8))d.enemies.add(v.getAsString()); return d;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        var c=new ListTag(); claims.forEach((p,a)->{var t=actor(a);t.putLong("pos",p);c.add(t);});tag.put("claims",c);
        var s=new ListTag();ships.forEach((id,a)->{var t=actor(a);t.putUUID("id",id);s.add(t);});tag.put("ships",s);
        var h=new ListTag();hotShips.forEach(id->h.add(StringTag.valueOf(id.toString())));tag.put("hot",h);
        var e=new ListTag();enemies.forEach(id->e.add(StringTag.valueOf(id)));tag.put("enemies",e);return tag;
    }
}
