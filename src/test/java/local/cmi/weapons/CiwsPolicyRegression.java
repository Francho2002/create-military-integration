package local.cmi.weapons;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
        String compact=Files.readString(Path.of("src/main/java/local/cmi/weapons/CompactCoordinator.java"));
        check(compact.contains("CiwsCoordinator.hasConfiguredMode(mount)")&&compact.contains("CiwsCoordinator.controls(mount)")&&compact.contains("CombatServices.contacts(level)"),"absent flag preserves general mode; false disables CMI");
        System.out.println("OK: CIWS track, IFF, incoming threat, deterministic lock, solver gates, reservation and one-use authorization");
    }
}
