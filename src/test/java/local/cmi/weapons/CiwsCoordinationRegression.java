package local.cmi.weapons;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import local.cmi.core.ReservationBook;
import local.cmi.core.Relation;
import local.cmi.core.TargetId;

/** Pure multi-mount assignment and bounded-preparation regression scenarios. */
public final class CiwsCoordinationRegression {
    private static int scenarios;

    private CiwsCoordinationRegression() {}

    public static void main(String[] args) throws Exception {
        UUID shooter = id(1), mountA = id(11), mountB = id(12), mountC = id(13);
        UUID x = id(101), y = id(102), urgent = id(103);
        var reservations = new ReservationBook<TargetId>();
        long now = 100;

        // A: a conflict on X falls through to Y without recomputing plans.
        List<CiwsPolicy.Candidate> two = List.of(candidate(x, 10, 1, 100), candidate(y, 10, 2, 80));
        check(reserve(reservations, x, mountA, now, 10), "A: CIWS A reserves X");
        UUID assignedB = firstReservable(reservations, CiwsCoordinationPolicy.orderedCandidates(null, two, null, 8), mountB, now);
        check(y.equals(assignedB), "A: CIWS B skips reserved X and takes Y");

        // B: one threat remains exclusive to a single CIWS.
        var oneThreat = new ReservationBook<TargetId>();
        check(reserve(oneThreat, x, mountA, now, 10), "B: first CIWS reserves the only threat");
        check(firstReservable(oneThreat, CiwsCoordinationPolicy.orderedCandidates(null, List.of(candidate(x, 10, 1, 1)), null, 8), mountB, now) == null,
                "B: second CIWS cannot double-assign the only threat");

        // C: three mounts can distribute two threats, leaving the third without a target.
        var twoThreats = new ReservationBook<TargetId>();
        check(reserve(twoThreats, x, mountA, now, 10), "C: CIWS A owns X");
        UUID assignmentB = firstReservable(twoThreats, CiwsCoordinationPolicy.orderedCandidates(null, two, null, 8), mountB, now);
        check(y.equals(assignmentB), "C: CIWS B owns Y");
        check(firstReservable(twoThreats, CiwsCoordinationPolicy.orderedCandidates(null, two, null, 8), mountC, now) == null,
                "C: third CIWS gets no already-reserved threat");

        // D: the shared reservation book permits reuse after its real expiry.
        var expiring = new ReservationBook<TargetId>();
        check(reserve(expiring, x, mountA, 20, 10), "D: old reservation starts");
        check(!reserve(expiring, x, mountB, 30, 10), "D: reservation remains exclusive through its inclusive until tick");
        expiring.release(target(x), mountB);
        check(!reserve(expiring, x, mountB, 30, 10), "D: another mount cannot release the reservation owner's lease");
        expiring.release(target(x), mountA);
        check(reserve(expiring, x, mountB, 30, 10), "D: release by the owner permits immediate reassignment");
        check(!reserve(expiring, x, mountA, 40, 10), "D: reassigned lease remains exclusive through its until tick");
        check(reserve(expiring, x, mountA, 41, 10), "D: an expired reservation can be assigned anew");

        // E: lost ownership cannot retain a reservation or pass the existing authorization policy.
        var ownership = new ReservationBook<TargetId>();
        check(reserve(ownership, x, mountA, now, 10), "E: preliminary reservation exists before ownership loss");
        check(!CiwsPolicy.eligible(true, true, shooter, null, null, null, Relation.UNKNOWN, false),
                "E: unknown owner fails closed");
        ownership.release(target(x), mountA);
        check(reserve(ownership, x, mountB, now, 10), "E: released own reservation is available");

        // F: changing UUID starts a fresh, one-sweep lock.
        CiwsPolicy.Lock lockX = CiwsPolicy.observe(CiwsPolicy.Lock.empty(), candidate(x, 10, 3, 30));
        lockX = CiwsPolicy.observe(lockX, candidate(x, 15, 3, 30));
        CiwsPolicy.Lock lockY = CiwsPolicy.observe(lockX, candidate(y, 20, 1, 10));
        check(lockY.samples() == 1 && y.equals(lockY.target()) && !CiwsPolicy.shouldFire(lockY, Long.MIN_VALUE),
                "F: X's two-sweep lock is not inherited by Y");

        // G: a mount without an admissible plan takes no reservation; bounded preparation cedes the target.
        var noSolution = new ReservationBook<TargetId>();
        check(CiwsCoordinationPolicy.orderedCandidates(null, List.of(), null, 8).isEmpty(), "G: no solution produces no candidate");
        check(reserve(noSolution, x, mountB, now, 10), "G: a mount with a valid plan may still take that target");
        check(!CiwsCoordinationPolicy.preparationExpired(now, now + 39) &&
                CiwsCoordinationPolicy.preparationExpired(now, now + CiwsCoordinationPolicy.PREPARING_BUDGET_TICKS),
                "G: preparation has a fixed 40-tick ceiling");
        check(CiwsCoordinationPolicy.preparationExpired(Long.MAX_VALUE - 40, Long.MAX_VALUE),
                "G: preparation expires exactly at the representable 40-tick boundary");
        check(!CiwsCoordinationPolicy.preparationExpired(Long.MAX_VALUE - 39, Long.MAX_VALUE),
                "G: preparation is not expired one tick before the 40-tick boundary near Long.MAX_VALUE");
        check(CiwsCoordinationPolicy.preparationExpired(0, Long.MAX_VALUE),
                "G: a maximum non-negative elapsed time expires without subtraction overflow");
        check(CiwsCoordinationPolicy.preparationExpired(-1, 10) &&
                CiwsCoordinationPolicy.preparationExpired(10, -1) &&
                CiwsCoordinationPolicy.preparationExpired(10, 9),
                "G: negative or backward preparation clocks fail closed");
        check(CiwsCoordinationPolicy.retryDeferred(x, CiwsCoordinationPolicy.retryUntil(now + 40), now + 49) &&
                !CiwsCoordinationPolicy.retryDeferred(x, CiwsCoordinationPolicy.retryUntil(now + 40), now + 50) &&
                CiwsCoordinationPolicy.orderedCandidates(null, List.of(candidate(x, 15, 1, 1)), x, 8).isEmpty(),
                "G: timeout releases the target and defers only the same mount's immediate reacquisition");
        check(CiwsCoordinationPolicy.retryDeferred(x, -1, now),
                "G: a malformed negative retry deadline keeps the target deferred fail-closed");
        check(CiwsCoordinationPolicy.retryUntil(Long.MAX_VALUE - 5) == Long.MAX_VALUE &&
                CiwsCoordinationPolicy.retryDeferred(x, Long.MAX_VALUE, Long.MAX_VALUE - 1) &&
                !CiwsCoordinationPolicy.retryDeferred(x, Long.MAX_VALUE, Long.MAX_VALUE),
                "G: retry deadline saturates safely at Long.MAX_VALUE and expires at its boundary");

        // H: a valid in-flight reservation remains exclusive while another mount uses its alternative.
        var inFlight = new ReservationBook<TargetId>();
        check(reserve(inFlight, x, mountA, now, 100), "H: in-flight X retains its long reservation");
        List<CiwsPolicy.Candidate> priorityChange = List.of(candidate(x, 10, 0.5, 1), candidate(y, 10, 2, 10));
        List<CiwsPolicy.Candidate> keepY = CiwsCoordinationPolicy.orderedCandidates(y, priorityChange, null, 8);
        check(keepY.get(0).id().equals(y) &&
                y.equals(firstReservable(inFlight, keepY, mountB, now)) &&
                !reserve(inFlight, x, mountB, now, 10),
                "H: new urgency cannot steal valid in-flight X; current assignable Y is retained");

        // I: a repeated physical timestamp does not create another lock sample.
        CiwsPolicy.Lock oneSweep = CiwsPolicy.observe(CiwsPolicy.Lock.empty(), candidate(x, 20, 1, 1));
        CiwsPolicy.Lock duplicateSweep = CiwsPolicy.observe(oneSweep, candidate(x, 20, 1, 1));
        check(oneSweep.samples() == 1 && duplicateSweep.samples() == 1, "I: duplicate sweep is not counted twice");

        // J: only the existing ENEMY policy can authorize the owner-known projectile.
        check(!eligible(shooter, id(50), Relation.FRIEND) && !eligible(shooter, id(51), Relation.NEUTRAL) &&
                !eligible(shooter, id(52), Relation.UNKNOWN) && eligible(shooter, id(53), Relation.ENEMY),
                "J: FRIEND, NEUTRAL and UNKNOWN remain rejected; ENEMY remains eligible");

        // K: a mount sees only the candidates supplied by its own radar snapshot.
        List<CiwsPolicy.Candidate> radarBTracks = List.of(candidate(y, 30, 3, 20));
        List<CiwsPolicy.Candidate> local = CiwsCoordinationPolicy.orderedCandidates(null, radarBTracks, null, 8);
        check(local.size() == 1 && local.get(0).id().equals(y) && local.stream().noneMatch(c -> c.id().equals(x)),
                "K: another radar's unobserved target is not imported into this mount's candidates");

        // L: bounded tracks, plans and projectile lifetime remain unchanged.
        List<CiwsPolicy.Candidate> many = new ArrayList<>();
        for (int i = 0; i < 16; i++) many.add(candidate(id(200 + i), 40, 1 + i, i));
        check(CiwsCoordinationPolicy.orderedCandidates(null, many, null, 8).size() == 8,
                "L: deterministic selector respects the eight-candidate cap");
        String coordinator = Files.readString(Path.of("src/main/java/local/cmi/weapons/CiwsCoordinator.java"));
        check(coordinator.contains("MAX_TRACKS = 128, MAX_CANDIDATES = 8, MAX_FLIGHT_TICKS = 200"),
                "L: physical-track and lifetime budgets remain 128/8/200");
        check(coordinator.contains("Set<UUID> solvedTracks = new HashSet<>()") &&
                coordinator.contains("if (!solvedTracks.add(id)) continue;"),
                "L: duplicate radar identities are discarded before motion/solver work");

        System.out.println("OK: 12 multi-CIWS coordination scenarios; local radar, exclusive reservations, lock reset, timeout and fixed budgets");
    }

    private static UUID firstReservable(ReservationBook<TargetId> reservations, List<CiwsPolicy.Candidate> ordered,
                                        UUID mount, long now) {
        for (CiwsPolicy.Candidate candidate : ordered)
            if (reserve(reservations, candidate.id(), mount, now, 10)) return candidate.id();
        return null;
    }

    private static boolean reserve(ReservationBook<TargetId> reservations, UUID target, UUID mount, long now, int ttl) {
        return reservations.reserve(target(target), mount, now, ttl);
    }

    private static TargetId target(UUID id) { return new TargetId(TargetId.Kind.ENTITY, id); }
    private static CiwsPolicy.Candidate candidate(UUID id, long scan, double closest, double distance) {
        return new CiwsPolicy.Candidate(id, scan, closest, distance);
    }
    private static boolean eligible(UUID shooter, UUID owner, Relation relation) {
        return CiwsPolicy.eligible(true, true, shooter, owner, null, null, relation, relation == Relation.ENEMY);
    }
    private static UUID id(long value) { return new UUID(0, value); }

    private static void check(boolean condition, String description) {
        scenarios++;
        if (!condition) throw new AssertionError(description);
    }
}
