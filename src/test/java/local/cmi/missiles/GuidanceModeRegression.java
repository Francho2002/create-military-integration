package local.cmi.missiles;

/** Runs on a plain Java 21 JVM; does not initialize Minecraft. */
public final class GuidanceModeRegression {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        // An absent marker has the same decision with or without an attribution-only owner.
        check(!GuidanceMode.managed(false, false, false), "native and owner-only remain native");
        check(GuidanceMode.managed(true, false, false), "explicit marker");
        check(GuidanceMode.managed(false, true, false), "legacy designation");
        check(GuidanceMode.managed(false, false, true), "legacy interceptor");
        for (int flags = 0; flags < 8; flags++) {
            boolean marker = (flags & 1) != 0, designated = (flags & 2) != 0, interceptor = (flags & 4) != 0;
            boolean latch = GuidanceMode.latchOnLoss(marker, designated, interceptor);
            check(latch == (flags != 0), "loss must latch only managed mode: " + flags);
            check(GuidanceMode.managed(latch, false, false) == (flags != 0), "mode after target loss: " + flags);
        }
        String ir = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/mixin/missiles/InfraredGuidanceMixin.java"));
        int acquire = ir.indexOf("private void cmi$acquire(");
        int guide = ir.indexOf("private void cmi$guide(");
        check(acquire >= 0 && guide > acquire, "both Neo Warfare hooks exist");
        for (String method : new String[]{ir.substring(acquire, guide), ir.substring(guide)}) {
            int guard = method.indexOf("if (!cmi$managed()) return;");
            int cancel = method.indexOf("ci.cancel();");
            check(guard >= 0 && cancel > guard, "native method returns before cancellation");
        }
        String flight = ir.substring(guide);
        check(flight.contains("!ManagedGuidanceData.validMode(cmi$data())) { cmi$abortFlight(server, missile);"), "malformed mode aborts flight");
        check(flight.contains("!tag.hasUUID(\"cmiOwner\")) { cmi$abortFlight(server, missile);"), "missing owner aborts flight");
        check(flight.contains("found.isEmpty()") && flight.contains("!CombatServices.canEngage(server, actor, found.get())) { cmi$abortFlight(server, missile);"), "lost or unauthorized target aborts flight");
        check(flight.contains("seeker.trackFov() / 2))) { cmi$abortFlight(server, missile);"), "lost FOV aborts flight");
        String war = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/mixin/missiles/IRSeekerTargetLossMixin.java"));
        int guard = war.indexOf("if (!ManagedGuidanceData.managed(");
        int reset = war.indexOf("target = null;");
        check(guard >= 0 && reset > guard, "Warium reset requires explicit managed mode");
        System.out.println("GuidanceMode regression checks passed");
    }
}
