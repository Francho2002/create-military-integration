package local.cmi.missiles;

import java.util.List;
import java.util.UUID;

/** Static lock-policy regression; no Minecraft runtime is loaded. */
public final class RadarSpearLockRegression {
    private RadarSpearLockRegression() {}
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID c = UUID.fromString("00000000-0000-0000-0000-000000000003");

        require(RadarSpearLockPolicy.select(null, List.of()) == null, "Sable without a physical radar track cannot be acquired");
        require(RadarSpearLockPolicy.fresh(101, 100), "the immediately preceding physical radar sweep remains usable");
        require(!RadarSpearLockPolicy.fresh(102, 100), "stale cached radar contacts must not authorize firing");
        require(!RadarSpearLockPolicy.fresh(100, 101), "future-dated radar tracks must be rejected");
        var farther = new RadarSpearLockPolicy.Candidate(a, 100, 10);
        var nearer = new RadarSpearLockPolicy.Candidate(b, 25, 10);
        require(RadarSpearLockPolicy.select(null, List.of(farther, nearer)).id().equals(b), "new lock must select nearest candidate");
        require(RadarSpearLockPolicy.select(a, List.of(farther, nearer)).id().equals(a), "valid current lock must survive a nearer candidate");
        require(RadarSpearLockPolicy.select(a, List.of(nearer)).id().equals(b), "a lost radar track must release the previous lock");
        var tieA = new RadarSpearLockPolicy.Candidate(c, 25, 10);
        require(RadarSpearLockPolicy.select(null, List.of(tieA, nearer)).id().equals(b), "equal distances must use UUID lexical order");

        var first = RadarSpearLockPolicy.observe(RadarSpearLockPolicy.Lock.empty(), farther);
        require(first.samples() == 1 && !RadarSpearLockPolicy.locked(first), "a single radar sweep must not fire");
        var repeated = RadarSpearLockPolicy.observe(first, farther);
        require(repeated.samples() == 1 && !RadarSpearLockPolicy.locked(repeated), "same cached sweep must not count twice");
        var secondSweep = new RadarSpearLockPolicy.Candidate(a, 90, 15);
        var locked = RadarSpearLockPolicy.observe(repeated, secondSweep);
        require(locked.samples() == 2 && RadarSpearLockPolicy.locked(locked), "two distinct radar sweeps establish a lock");
        require(RadarSpearLockPolicy.shouldFire(locked, 10), "a locked, new radar sweep may request native firing once");
        require(!RadarSpearLockPolicy.shouldFire(locked, 15), "the same radar sweep cannot request a duplicate native firing");
        var lost = RadarSpearLockPolicy.observe(locked, null);
        require(lost.target() == null && lost.samples() == 0, "loss of current radar track clears lock");
        var reacquired = RadarSpearLockPolicy.observe(lost, nearer);
        require(reacquired.target().equals(b) && reacquired.samples() == 1, "new target must acquire from scratch");
        System.out.println("OK: radar track gating inputs, stable lock, loss, nearest selection and deterministic tie-break.");
    }
}
