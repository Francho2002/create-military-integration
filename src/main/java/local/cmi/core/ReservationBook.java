package local.cmi.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread reservation state without entity references; expiration is in game ticks. */
public final class ReservationBook<T> {
    private record Reservation(UUID owner, long until) {}
    private final Map<T, Reservation> entries = new HashMap<>();

    public boolean reserve(T target, UUID owner, long now, int ttl) {
        if (target == null || owner == null) return false;
        entries.values().removeIf(r -> r.until() < now);
        var existing = entries.get(target);
        if (existing != null && !existing.owner().equals(owner)) return false;
        long duration = Math.max(1, ttl);
        long until = now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
        entries.put(target, new Reservation(owner, until));
        return true;
    }

    public void release(T target, UUID owner) {
        var existing = entries.get(target);
        if (existing != null && existing.owner().equals(owner)) entries.remove(target);
    }
}
