package local.cmi.weapons;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;
import local.cmi.core.Relation;
import local.cmi.core.ReservationBook;

/** Plain Java regression: no Minecraft, GameTest or server is started. */
public final class CiwsPolicyRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b=UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID own=UUID.fromString("00000000-0000-0000-0000-000000000003");
        check(CiwsPolicy.projectileId(true,a.toString(),101,100).equals(a),"real PROJECTILE UUID");
        check(CiwsPolicy.projectileId(false,a.toString(),101,100)==null,"non-projectile track");
        check(CiwsPolicy.projectileId(true,null,101,100)==null,"absent track id");
        check(CiwsPolicy.projectileId(true,"1-1-1-1-1",101,100)==null,"non-canonical id");
        check(CiwsPolicy.projectileId(true,"malformed",101,100)==null,"malformed id");
        check(CiwsPolicy.projectileId(true,a.toString(),101,99)==null,"stale radar sweep");
        check(CiwsPolicy.projectileId(true,a.toString(),101,102)==null,"future radar sweep");
        check(CiwsPolicy.projectileId(true,a.toString(),101,Long.MIN_VALUE)==null,"overflow timestamp");
        check(CiwsPolicy.withinBudget(128,128)&&!CiwsPolicy.withinBudget(129,128),"track budget");
        check(CiwsPolicy.withinBudget(8,8)&&!CiwsPolicy.withinBudget(9,8),"solver budget");
        check(CiwsPolicy.eligible(true,true,own,a,null,null,Relation.ENEMY,true),"hostile projectile");
        check(!CiwsPolicy.eligible(true,true,own,own,null,null,Relation.FRIEND,true),"own projectile");
        check(!CiwsPolicy.eligible(true,true,own,a,null,null,Relation.FRIEND,true),"allied projectile");
        check(!CiwsPolicy.eligible(true,true,own,a,null,null,Relation.NEUTRAL,true),"neutral projectile");
        check(!CiwsPolicy.eligible(true,true,own,a,null,null,Relation.UNKNOWN,true),"unknown projectile");
        check(!CiwsPolicy.eligible(true,true,own,null,null,null,Relation.ENEMY,true),"missing owner");
        check(!CiwsPolicy.eligible(true,true,own,a,b,b,Relation.ENEMY,true),"own platform");
        check(!CiwsPolicy.eligible(true,true,own,a,null,null,Relation.ENEMY,false),"protected target");
        check(CiwsPolicy.sensorAllows(true,true,false),"normal projectile: both filters enabled");
        check(CiwsPolicy.sensorAllows(true,false,false),"normal projectile: projectile filter alone is sufficient");
        check(!CiwsPolicy.sensorAllows(false,true,false),"normal projectile: disabled projectile filter rejects");
        check(!CiwsPolicy.sensorAllows(false,false,false),"normal projectile: both filters disabled reject");
        check(CiwsPolicy.sensorAllows(true,true,true),"contraption projectile: both physical/category filters enabled");
        check(!CiwsPolicy.sensorAllows(true,false,true),"contraption projectile: disabled physical query rejects");
        check(!CiwsPolicy.sensorAllows(false,true,true),"contraption projectile: disabled category filter rejects");
        check(!CiwsPolicy.sensorAllows(false,false,true),"contraption projectile: both filters disabled reject");
        check(!CiwsPolicy.eligible(true,true,own,null,null,null,Relation.UNKNOWN,false),"PROJECTILE track without known owner cannot authorize fire");
        check(CiwsPolicy.approaching(10,0,0,-2,0,0)==5,"incoming");
        check(Double.isNaN(CiwsPolicy.approaching(10,0,0,2,0,0)),"receding");
        check(Double.isNaN(CiwsPolicy.approaching(10,0,0,0,0,0)),"stationary");
        check(Double.isNaN(CiwsPolicy.approaching(10,0,0,Double.NaN,0,0)),"NaN velocity");
        check(Double.isNaN(CiwsPolicy.approaching(10,0,0,Double.POSITIVE_INFINITY,0,0)),"infinite velocity");
        double tolerance=CiwsPolicy.tolerance(.2,.4,.6);
        check(tolerance>0&&tolerance<.1,"inscribed target radius");
        check(Double.isNaN(CiwsPolicy.tolerance(0,.4,.6)),"empty target hitbox");
        check(CiwsPolicy.acceptableSolution(true,true,tolerance/2,tolerance,3,10),"solution accepted");
        check(!CiwsPolicy.acceptableSolution(false,true,0,tolerance,3,10),"no solution");
        check(!CiwsPolicy.acceptableSolution(true,false,0,tolerance,3,10),"outside firing arc");
        check(!CiwsPolicy.acceptableSolution(true,true,tolerance*2,tolerance,3,10),"unaligned actual barrel");
        check(!CiwsPolicy.acceptableSolution(true,true,Double.NaN,tolerance,3,10),"non-finite miss");
        check(!CiwsPolicy.acceptableSolution(true,true,0,tolerance,11,10),"beyond projectile lifetime");
        var ca=new CiwsPolicy.Candidate(a,100,4,100); var cb=new CiwsPolicy.Candidate(b,100,2,200);
        check(CiwsPolicy.select(null,List.of(ca,cb)).id().equals(b),"closest approach prioritization");
        check(CiwsPolicy.select(a,List.of(cb,ca)).id().equals(a),"retain current lock");
        var tieA=new CiwsPolicy.Candidate(a,100,2,200);
        check(CiwsPolicy.select(null,List.of(cb,tieA)).id().equals(a),"deterministic UUID tie");
        var lock=CiwsPolicy.observe(CiwsPolicy.Lock.empty(),ca);
        check(!CiwsPolicy.shouldFire(lock,Long.MIN_VALUE),"first sweep cannot fire");
        lock=CiwsPolicy.observe(lock,ca);
        check(lock.samples()==1,"same timestamp not counted twice");
        lock=CiwsPolicy.observe(lock,new CiwsPolicy.Candidate(a,105,4,100));
        check(CiwsPolicy.shouldFire(lock,Long.MIN_VALUE),"second distinct sweep locks");
        check(!CiwsPolicy.shouldFire(lock,105),"same authorization cannot request twice");
        check(CiwsPolicy.observe(lock,null).target()==null,"lost track clears lock");
        check(CiwsPolicy.observe(lock,new CiwsPolicy.Candidate(a,115,4,100)).samples()==1,"missed sweep starts a new lock");
        check(CiwsPolicy.observe(lock,cb).samples()==1,"new target starts a new lock");
        var token=new CiwsPolicy.Once(); check(token.consume()&&!token.consume(),"fire permit is single use");
        var reservations=new ReservationBook<UUID>();
        check(reservations.reserve(a,own,10,5),"first CIWS reserves");
        check(!reservations.reserve(a,b,10,5),"second CIWS cannot reserve same target");
        reservations.release(a,b); check(!reservations.reserve(a,b,11,5),"wrong owner cannot release");
        reservations.release(a,own); check(reservations.reserve(a,b,11,5),"release permits another CIWS");
        check(reservations.reserve(a,own,17,5),"expired reservation can be reused");
        String coordinator=Files.readString(Path.of("src/main/java/local/cmi/weapons/CiwsCoordinator.java"));
        check(coordinator.contains("TrackCategory.PROJECTILE") && coordinator.contains("radar.getTracks()"),"physical radar authority");
        check(coordinator.indexOf("withinBudget(++scanned, MAX_TRACKS)")<coordinator.indexOf("t.trackCategory() != TrackCategory.PROJECTILE"),"entire radar snapshot has a bounded iteration");
        check(!coordinator.contains("CombatServices.contacts(")&&!coordinator.contains("getAllEntities(")&&!coordinator.contains("Sensor.scan("),"no global sensor");
        check(coordinator.contains("TrajectorySafety.clear(")&&coordinator.contains("CombatServices.reserve(")&&coordinator.contains("WeaponAdapters.fire("),"safety, reservation and native fire");
        check(coordinator.contains("FtbCompat.claimFriendly(")&&coordinator.contains(").isPresent()"),"all FTB claims block CIWS trajectory");
        check(coordinator.indexOf("if (!safePath(level, actor, cannon, plan.target()")<coordinator.indexOf("WeaponAdapters.fire("),"unsafe actual trajectory blocks request");
        check(coordinator.contains("if (!safePath(level, actor, cannon, target, pivot, solver, solution.direction()"),"blocked planned trajectory is not reserved");
        check(!coordinator.contains("new AbstractCannonProjectile")&&!coordinator.contains("CombatServices.neutralize("),"no synthetic spawn or predicted neutralization");
        String radarTagPath="data/create_radar/tags/entity_type/radar_projectile.json";
        String obsoleteRadarTagPath="data/create_radar/tags/entity_types/radar_projectile.json";
        Path sourceRadarTag=Path.of("src/main/resources").resolve(radarTagPath);
        Path obsoleteSourceRadarTag=Path.of("src/main/resources").resolve(obsoleteRadarTagPath);
        check(Files.isRegularFile(sourceRadarTag),"Minecraft 1.21.1 singular entity_type tag path exists");
        check(!Files.exists(obsoleteSourceRadarTag),"obsolete plural entity_types source path is absent");
        String radarTag=Files.readString(sourceRadarTag).replaceAll("\\s+","");
        check(radarTag.equals("{\"replace\":false,\"values\":[\"cbcmodernwarfare:munitions_contraption\"]}"),"only the verified Neo Warfare multiblock entity is tagged as a radar projectile");
        try (ZipFile artifact = new ZipFile("build/libs/create-military-integration-0.1.0-dev.jar")) {
            check(artifact.getEntry(radarTagPath)!=null,"singular radar tag is packaged in final JAR");
            check(artifact.getEntry(obsoleteRadarTagPath)==null,"final JAR excludes obsolete plural radar tag path");
            String packaged;
            try (var input=artifact.getInputStream(artifact.getEntry(radarTagPath))) {
                packaged=new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\s+","");
            }
            check(packaged.equals("{\"replace\":false,\"values\":[\"cbcmodernwarfare:munitions_contraption\"]}"),"final JAR has exact append-only projectile tag");
        }
        String combat=Files.readString(Path.of("src/main/java/local/cmi/core/CombatServices.java"));
        check(combat.contains("e instanceof riftyboi.cbcmodernwarfare.munitions.contraptions.MunitionsPhysicsContraptionEntity"),"real multiblock entity remains recognized as a projectile");
        check(coordinator.contains("CiwsPolicy.projectileId(track.trackCategory() == TrackCategory.PROJECTILE, track.id(), now, track.scannedTime())")&&coordinator.contains("level.getEntity(id)")&&coordinator.contains("CombatServices.resolve(level, new TargetId(TargetId.Kind.ENTITY, id))"),"radar UUID resolves back to live entity and current contact");
        check(coordinator.contains("CombatServices.relation(level, actor, contact)")&&coordinator.contains("CombatServices.canEngage(level, actor, contact)"),"radar classification never replaces ownership/IFF authorization");
        check(coordinator.contains("filters.cmi$scanContraptions()")&&coordinator.contains("filters.cmi$scanProjectiles()"),"CIWS reads both physical radar filters");
        check(coordinator.contains("if (!scanProjectiles) return null;")&&!coordinator.contains("if (!filters.cmi$scanContraptions() || !filters.cmi$scanProjectiles()) return null;"),"disabled projectile filter rejects globally without rejecting normal projectiles when contraption scan is off");
        check(coordinator.contains("target instanceof AbstractContraptionEntity")&&coordinator.contains("CiwsPolicy.sensorAllows(snapshot.scanProjectiles(),"),"resolved entity type selects the additional contraption filter");
        String plans=coordinator.substring(coordinator.indexOf("private static List<Plan> plans("),coordinator.indexOf("private static RadarSnapshot projectileTracks("));
        String held=coordinator.substring(coordinator.indexOf("private static boolean heldTargetStillValid("),coordinator.indexOf("private static boolean sensorAllows("));
        check(plans.indexOf("level.getEntity(id)")>=0&&plans.indexOf("sensorAllows(radarSnapshot, target)")>plans.indexOf("level.getEntity(id)"),"acquisition applies radar sensor filter after UUID resolves to an entity");
        check(held.indexOf("level.getEntity(id)")>=0&&held.indexOf("sensorAllows(radarSnapshot, entity)")>held.indexOf("level.getEntity(id)"),"held/reserved target revalidation applies the same filter after resolving entity");
        String mixins=Files.readString(Path.of("src/main/resources/cmi.mixins.json"));
        check(mixins.contains("weapons.RadarScanningFilterAccess"),"read-only radar filter accessor is registered");
        String compact=Files.readString(Path.of("src/main/java/local/cmi/weapons/CompactCoordinator.java"));
        check(compact.contains("CiwsCoordinator.hasConfiguredMode(mount)")&&compact.contains("CiwsCoordinator.controls(mount)")&&compact.contains("CombatServices.contacts(level)"),"absent flag preserves general mode; false disables CMI");
        System.out.println("OK: CIWS track, IFF, incoming threat, deterministic lock, solver gates, reservation and one-use authorization");
    }
}
