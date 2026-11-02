package local.cmi.core;

/** Plain JVM checks for scoped automatic-shot attribution. */
public final class ShotContextRegression {
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        Actor actor = new Actor(java.util.UUID.randomUUID(), "", null);
        ShotContext.push(actor);
        check(!ShotContext.managed(), "owner-only manual shot remains native");
        ShotContext.pop();
        ShotContext.runManaged(() -> {
            check(ShotContext.managed(), "managed scope");
            ShotContext.push(actor);
            check(ShotContext.managed() && ShotContext.current() == actor, "nested ownership scope");
            ShotContext.pop();
            try { ShotContext.runManaged(() -> { throw new IllegalStateException(); }); }
            catch (IllegalStateException expected) { }
            check(ShotContext.managed(), "nested exception restores outer scope");
        });
        check(!ShotContext.managed(), "scope restored");
        try { ShotContext.runManaged(() -> { throw new IllegalStateException(); }); }
        catch (IllegalStateException expected) { }
        check(!ShotContext.managed(), "exception restores manual mode");
        String main = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/CreateMilitaryIntegration.java"));
        check(main.contains("if(ShotContext.managed())t.putBoolean(\"cmiManagedShot\",true)"), "manual spawn receives no managed marker");
        check(main.contains("CombatServices.neutralize(level,actor,contact.get())) event.getAffectedEntities().remove(target)"), "neutralized projectile removed from blast victims");
        String hit = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/mixin/safety/CannonInterceptionMixin.java"));
        check(hit.split("!self.getPersistentData\\(\\).getBoolean\\(\"cmiManagedShot\"\\)", -1).length == 3, "both collision hooks require managed shot");
        String ownership = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/mixin/safety/ShotOwnershipMixin.java"));
        check(ownership.contains("MountedMunitionsLauncherContraption") && ownership.contains("@WrapMethod(method=\"fireShot\")"), "Neo Warfare launcher uses the authentic scoped fireShot hook");
        check(ownership.contains("ShotContext.push(CombatServices.actor(level,carrier))") && ownership.contains("finally{ShotContext.pop();}"), "launcher provenance is limited to its native shot call");
        check(main.contains("ShotContext.current()") && main.contains("CombatServices.projectile(event.getEntity())") && main.contains("t.putUUID(\"cmiOwner\",actor.owner())"), "entity join receives owner only from launch context");
        check(main.contains("t.putUUID(\"cmiPlatform\",actor.platform())"), "platform provenance is copied when known");
        String adapters = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/local/cmi/weapons/WeaponAdapters.java"));
        check(adapters.contains("ShotContext.runManaged(() -> cannon.fireShot(level, entity))"), "CIWS dispatches through native cannon fire with managed provenance");
        System.out.println("ShotContext regression checks passed");
    }
}
