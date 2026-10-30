package local.cmi.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Pure ownership/IFF policy checks; does not start Minecraft. */
public final class ShipOwnershipRegression {
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }

    public static void main(String[] args) throws Exception {
        UUID shipA=UUID.randomUUID(), ownerX=UUID.randomUUID(), oldOwner=UUID.randomUUID(), shooter=UUID.randomUUID();
        CmiData data=new CmiData();
        data.bindShip(shipA,new Actor(oldOwner,"iff-blue",shipA));
        check(ShipOwnership.assignLoaded(data,List.of(shipA),shipA,ownerX),"loaded Sable accepted");
        Actor bound=data.ship(shipA);
        check(bound.owner().equals(ownerX),"owner updated");
        check(bound.iff().equals("iff-blue"),"IFF preserved on owner update");
        check(bound.platform().equals(shipA),"Actor platform is its Sable UUID");

        CompoundTag saved=data.save(new CompoundTag(),null);
        CmiData restored=CmiData.load(saved,null);
        check(restored.ship(shipA).equals(bound),"ship ownership persists through SavedData NBT");
        UUID absent=UUID.randomUUID();
        check(!ShipOwnership.assignLoaded(data,List.of(shipA),absent,ownerX),"unloaded/nonexistent Sable rejected");
        check(data.ship(absent)==null,"rejected Sable does not create association");
        check(!ShipOwnership.assignLoaded(data,List.of(shipA),shipA,null),"null owner rejected");

        data.hostile(shooter,ownerX,true);
        check(data.enemy(shooter,ownerX),"hostile UUID true");
        check(ShipOwnership.ownerRelation(shooter,ownerX,false,data.enemy(shooter,ownerX))==Relation.ENEMY,"hostile owned target is enemy");
        data.hostile(shooter,ownerX,false);
        check(!data.enemy(shooter,ownerX),"hostile UUID false");
        check(ShipOwnership.ownerRelation(shooter,ownerX,false,data.enemy(shooter,ownerX))==Relation.NEUTRAL,"non-hostile owned target is neutral");

        check(ShipOwnership.ownerRelation(shooter,null,false,true)==Relation.UNKNOWN,"unowned target remains unknown despite hostility flag");
        check(ShipOwnership.ownerRelation(null,ownerX,false,true)==Relation.UNKNOWN,"missing shooter owner remains unknown");
        check(ShipOwnership.ownerRelation(ownerX,ownerX,false,true)==Relation.FRIEND,"same owner remains friend");
        check(ShipOwnership.ownerRelation(shooter,ownerX,true,true)==Relation.FRIEND,"IFF/FTB friend overrides explicit hostility");

        String commands=Files.readString(Path.of("src/main/java/local/cmi/CreateMilitaryIntegration.java"));
        String combat=Files.readString(Path.of("src/main/java/local/cmi/core/CombatServices.java"));
        check(commands.contains("Commands.literal(\"ships\")")&&commands.contains("SubLevelContainer.getContainer(level)"),"ships command enumerates Sable container");
        check(commands.contains("Commands.literal(\"shipowner\")")&&commands.contains("!s.isRemoved()"),"shipowner requires a currently loaded Sable");
        check(commands.contains("Commands.literal(\"hostileuuid\")"),"UUID hostility command registered");
        check(commands.contains("Commands.literal(\"relation\")"),"read-only relation diagnostic registered");
        check(commands.contains("var be=l.getBlockEntity(p)"),"existing claim still resolves only main-level block entities");
        check(combat.contains("Iff.playerId(l,allegiance,c.owner())==Iff.Side.FRIEND")&&combat.contains("ShipOwnership.ownerRelation"),"friend check precedes explicit hostility");
        System.out.println("Ship ownership regression checks passed");
    }
}
