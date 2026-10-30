package local.cmi.core;

import java.util.Collection;
import java.util.UUID;

/** CMI-owned Sable ownership and the precedence rules for explicit hostility. */
public final class ShipOwnership {
    private ShipOwnership() {}

    /** Bind only a currently loaded Sable ID supplied by the server-side container. */
    public static boolean assignLoaded(CmiData data, Collection<UUID> loadedShipIds, UUID shipId, UUID ownerId) {
        if (data == null || loadedShipIds == null || shipId == null || ownerId == null || !loadedShipIds.contains(shipId)) return false;
        Actor previous = data.ship(shipId);
        String iff = previous == null || previous.iff() == null ? "" : previous.iff();
        data.bindShip(shipId, new Actor(ownerId, iff, shipId));
        return true;
    }

    /** Explicit CMI hostility applies only after ownership and IFF have ruled out friends. */
    public static Relation ownerRelation(UUID shooterOwner, UUID targetOwner, boolean iffFriendly, boolean explicitlyHostile) {
        if (shooterOwner == null) return Relation.UNKNOWN;
        if (targetOwner != null && shooterOwner.equals(targetOwner)) return Relation.FRIEND;
        if (iffFriendly) return Relation.FRIEND;
        if (targetOwner == null) return Relation.UNKNOWN;
        return explicitlyHostile ? Relation.ENEMY : Relation.NEUTRAL;
    }
}
