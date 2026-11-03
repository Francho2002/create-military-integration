package local.cmi.core;

import java.util.UUID;

/** Pure identity/lifetime regression. It does not simulate a world or claim a physical collision. */
public final class ProjectExplosivePermitRegression {
    private ProjectExplosivePermitRegression() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000082");
    private static final UUID OWNER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OWNER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID PLATFORM = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID RADAR = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID CANNON = UUID.fromString("00000000-0000-0000-0000-000000000300");

    private static ProjectExplosivePermitPolicy.Identity identity() {
        return new ProjectExplosivePermitPolicy.Identity("minecraft:overworld", TARGET, OWNER_A, null,
                10L, 20L, "create_radar:stationary_radar", RADAR, OWNER_A, "", null,
                CANNON, OWNER_A, "", PLATFORM, "ENEMY", false);
    }

    private static ProjectExplosivePermitPolicy.Identity alter(ProjectExplosivePermitPolicy.Identity b,
            String field, Object value) {
        String dimension = b.dimension(), type = b.radarType(), radarIff = b.radarIff(),
                actorIff = b.actorIff(), relation = b.relation();
        UUID target = b.target(), owner = b.targetOwner(), targetPlatform = b.targetPlatform(),
                radarInstance = b.radarInstance(), radarOwner = b.radarOwner(), radarPlatform = b.radarPlatform(),
                cannon = b.cannon(), actorOwner = b.actorOwner(), actorPlatform = b.actorPlatform();
        long mount = b.mountPosition(), radarPosition = b.radarPosition();
        boolean protectedTarget = b.protectedTarget();
        switch (field) {
            case "dimension" -> dimension = (String)value;
            case "target" -> target = (UUID)value;
            case "owner" -> owner = (UUID)value;
            case "targetPlatform" -> targetPlatform = (UUID)value;
            case "mount" -> mount = (Long)value;
            case "radarPosition" -> radarPosition = (Long)value;
            case "radarType" -> type = (String)value;
            case "radarInstance" -> radarInstance = (UUID)value;
            case "radarOwner" -> radarOwner = (UUID)value;
            case "radarIff" -> radarIff = (String)value;
            case "radarPlatform" -> radarPlatform = (UUID)value;
            case "cannon" -> cannon = (UUID)value;
            case "actorOwner" -> actorOwner = (UUID)value;
            case "actorIff" -> actorIff = (String)value;
            case "actorPlatform" -> actorPlatform = (UUID)value;
            case "relation" -> relation = (String)value;
            case "protected" -> protectedTarget = (Boolean)value;
            default -> throw new IllegalArgumentException(field);
        }
        return new ProjectExplosivePermitPolicy.Identity(dimension, target, owner, targetPlatform, mount,
                radarPosition, type, radarInstance, radarOwner, radarIff, radarPlatform, cannon,
                actorOwner, actorIff, actorPlatform, relation, protectedTarget);
    }

    public static void main(String[] args) {
        ProjectExplosivePermitPolicy.Identity issued = identity();
        check(ProjectExplosivePermitPolicy.valid(issued, identity(), true, false, 100, 300, 101),
                "same exact owner/radar/mount/cannon/actor identity remains valid during flight");
        check(!ProjectExplosivePermitPolicy.uniqueCbcSpawn(0), "no CBC entity join cannot receive an impact permit");
        check(ProjectExplosivePermitPolicy.uniqueCbcSpawn(1), "exactly one CBC entity join may receive an impact permit");
        check(!ProjectExplosivePermitPolicy.uniqueCbcSpawn(2),
                "multiple CBC joins are ambiguous, including a later spawn with incomplete managed metadata");
        check(!ProjectExplosivePermitPolicy.sameIdentity(issued, alter(issued, "owner", OWNER_B)),
                "owner A to owner B is rejected even if both are considered enemies");
        check(!ProjectExplosivePermitPolicy.sameIdentity(issued, alter(issued, "relation", "FRIEND")),
                "ENEMY to FRIEND relation change revokes the permit");
        check(!ProjectExplosivePermitPolicy.sameIdentity(issued, alter(issued, "relation", "NEUTRAL")),
                "neutral relation cannot satisfy a destructive permit");
        check(!ProjectExplosivePermitPolicy.sameIdentity(issued, alter(issued, "relation", "UNKNOWN")),
                "unknown relation cannot satisfy a destructive permit");
        check(!ProjectExplosivePermitPolicy.sameIdentity(issued, alter(issued, "protected", true)),
                "protected target invalidates permit identity");
        check(!ProjectExplosivePermitPolicy.sameIdentity(issued, alter(issued, "owner", null)),
                "missing owner is fail-closed");
        check(!ProjectExplosivePermitPolicy.valid(issued, identity(), false, false, 100, 300, 101), "opt-in OFF rejects");
        check(!ProjectExplosivePermitPolicy.valid(issued, identity(), true, false, 100, 300, 301), "expired permit rejects");
        check(!ProjectExplosivePermitPolicy.valid(issued, identity(), true, false, 100, 300, 99), "clock rollback rejects");
        check(!ProjectExplosivePermitPolicy.valid(issued, identity(), true, true, 100, 300, 101), "consumed permit rejects");
        check(!ProjectExplosivePermitPolicy.valid(issued, identity(), true, false, 300, 100, 101), "malformed lifetime rejects");
        check(!ProjectExplosivePermitPolicy.valid(issued, identity(), true, false, 100, 301, 101), "lifetime above 200 ticks rejects");

        Object[][] changes = {
                {"dimension", "minecraft:the_nether"}, {"target", UUID.randomUUID()},
                {"targetPlatform", PLATFORM}, {"mount", 11L}, {"radarPosition", 21L},
                {"radarType", "create_radar:radar_bearing"}, {"radarInstance", UUID.randomUUID()},
                {"radarOwner", OWNER_B}, {"radarIff", "changed-iff"}, {"radarPlatform", PLATFORM},
                {"cannon", UUID.randomUUID()}, {"actorOwner", OWNER_B}, {"actorIff", "changed-iff"},
                {"actorPlatform", UUID.randomUUID()}
        };
        for (Object[] change : changes)
            check(!ProjectExplosivePermitPolicy.sameIdentity(issued,
                    alter(issued, (String)change[0], change[1])), change[0] + " change revokes the permit");
        check(ProjectExplosivePermitPolicy.sameIdentity(issued, identity()),
                "exact immutable values remain equal across re-resolution");

        var oneShot = new ProjectExplosivePermitPolicy.OneShot();
        check(oneShot.consume() && oneShot.consumed(), "first physical callback consumes one-shot token");
        check(!oneShot.consume(), "double CBC callback cannot consume/authorize a second time");

        System.out.println("OK: PE impact permit identity, hostile-owner continuity, radar/cannon/actor binding, lifetime, opt-in and one-use policy");
    }
}
