package local.cmi.weapons;

import com.mrvew.firecontrol.ballistics.TargetMotion;
import com.mrvew.firecontrol.ballistics.V3;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import local.cmi.core.Relation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Pure Java motion-history and prediction-confidence regression; does not start Minecraft. */
public final class CiwsMotionRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static V3 v(double x, double y, double z) { return new V3(x, y, z); }
    private static V3 ballisticPosition(V3 position, V3 velocity, V3 acceleration, double ticks) {
        return position.add(velocity.scale(ticks)).add(acceleration.scale(0.5 * ticks * ticks));
    }

    public static void main(String[] args) throws Exception {
        UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID target=UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID otherTarget=UUID.fromString("00000000-0000-0000-0000-000000000022");
        var observer=new CiwsMotionTracker.Observer("minecraft:overworld", 12L, 34L, owner, null);
        var provenance=new CiwsMotionTracker.Provenance(UUID.fromString("00000000-0000-0000-0000-000000000031"), null);
        var tracker=new CiwsMotionTracker();
        V3 p0=v(100,20,5), v0=v(-4,0,0), acceleration=v(-.1,0,0);
        V3 p1=ballisticPosition(p0,v0,acceleration,5), v1=v(-4.5,0,0);
        V3 p2=ballisticPosition(p1,v1,acceleration,5), v2=v(-5,0,0);

        var first=tracker.observe(observer,target,provenance,101,p0,v0,102);
        check(first.result()==CiwsMotionTracker.Result.FIRST&&first.history().samples().size()==1,"first radar observation initializes history");
        var duplicate=tracker.observe(observer,target,provenance,101,p0,v0,102);
        check(duplicate.result()==CiwsMotionTracker.Result.DUPLICATE&&duplicate.history().samples().size()==1,"same scannedTime does not count twice");
        var second=tracker.observe(observer,target,provenance,106,p1,v1,107);
        check(second.result()==CiwsMotionTracker.Result.ADDED&&second.history().samples().size()==2,"next five-tick radar sweep appends one sample");
        var third=tracker.observe(observer,target,provenance,111,p2,v2,112);
        check(third.result()==CiwsMotionTracker.Result.ADDED&&third.history().samples().size()==3,"third consecutive sweep is retained in bounded window");
        var estimate=CiwsMotionPolicy.assess(third.history(),1.0);
        check(estimate.model()==CiwsMotionPolicy.Model.ACCELERATED,"three coherent sweeps confirm acceleration");
        check(Math.abs(estimate.acceleration().x()+.1)<1.0e-12&&estimate.acceleration().y()==0&&estimate.acceleration().z()==0,"negative X acceleration uses timestamp delta in ticks");
        check(estimate.acceleration().isFinite(),"acceleration components are finite");
        check(tracker.history(observer,target).samples().size()==3,"history stores no more than three observations");

        var axisHistory=historyForAcceleration(observer,target,provenance,v(.03,-.05,.07));
        var axisEstimate=CiwsMotionPolicy.assess(axisHistory,1.0);
        check(axisEstimate.model()==CiwsMotionPolicy.Model.ACCELERATED&&
                Math.abs(axisEstimate.acceleration().x()-.03)<1.0e-12&&
                Math.abs(axisEstimate.acceleration().y()+.05)<1.0e-12&&
                Math.abs(axisEstimate.acceleration().z()-.07)<1.0e-12,
                "stable positive, lateral and vertical acceleration components are estimated independently");

        var differentTarget=tracker.observe(observer,otherTarget,provenance,111,p2,v2,112);
        check(differentTarget.result()==CiwsMotionTracker.Result.FIRST&&tracker.history(observer,target)!=null,"target UUID separates histories");
        var differentRadar=new CiwsMotionTracker.Observer(observer.dimension(),observer.mountPosition(),35L,owner,null);
        check(tracker.observe(differentRadar,target,provenance,111,p2,v2,112).result()==CiwsMotionTracker.Result.FIRST,"radar binding separates histories");
        check(tracker.history(observer,target)==null,"changing radar at same mount invalidates prior source history");
        tracker.observe(observer,target,provenance,101,p0,v0,102);
        var changedOwner=new CiwsMotionTracker.Observer(observer.dimension(),observer.mountPosition(),36L,
                UUID.fromString("00000000-0000-0000-0000-000000000012"),null);
        check(tracker.observe(changedOwner,target,provenance,106,p1,v1,107).result()==CiwsMotionTracker.Result.FIRST,"owner/source change restarts history");
        check(tracker.history(observer,target)==null,"changed source purges prior mount history");

        var provenanceReset=tracker.observe(changedOwner,target,new CiwsMotionTracker.Provenance(
                UUID.fromString("00000000-0000-0000-0000-000000000032"),null),111,p2,v2,112);
        check(provenanceReset.result()==CiwsMotionTracker.Result.PROVENANCE_RESET&&provenanceReset.history().samples().size()==1,"target ownership/platform provenance change resets samples");
        var gap=tracker.observe(changedOwner,target,provenanceReset.history().provenance(),121,p2,v2,122);
        check(gap.result()==CiwsMotionTracker.Result.DISCONTINUITY&&gap.history().samples().size()==1,"skipped radar sweep invalidates continuity");
        check(tracker.observe(changedOwner,otherTarget,provenance,200,p0,v0,199).result()==CiwsMotionTracker.Result.INVALID,"future timestamp rejected");
        check(tracker.observe(changedOwner,target,provenance,122,v(Double.NaN,0,0),v0,122).result()==CiwsMotionTracker.Result.INVALID,"NaN sample rejected and history cleared");
        check(tracker.observe(changedOwner,target,provenance,122,p0,v(0,Double.POSITIVE_INFINITY,0),122).result()==CiwsMotionTracker.Result.INVALID,"infinite sample rejected");
        check(tracker.observe(changedOwner,target,provenance,122,p0,v0,122).result()==CiwsMotionTracker.Result.FIRST,"valid observation can only restart after malformed data");
        tracker.retain(changedOwner,Set.of());
        check(tracker.history(changedOwner,target)==null,"lost radar coverage or authorization clears target history");
        tracker.observe(changedOwner,target,provenance,130,p0,v0,130);
        tracker.prune(changedOwner.dimension(),CiwsMotionTracker.STALE_AFTER_TICKS+131);
        check(tracker.history(changedOwner,target)==null,"dimension game-time pruning expires stale value histories");

        var resetTracker=new CiwsMotionTracker();
        var resetFirst=resetTracker.observe(observer,target,provenance,101,p0,v0,102).history();
        var reset=resetTracker.observe(observer,target,provenance,111,p2,v2,112);
        check(reset.result()==CiwsMotionTracker.Result.DISCONTINUITY&&reset.history().samples().size()==1,"a ten-tick timestamp jump restarts rather than fabricating acceleration");
        check(resetFirst.samples().size()==1,"snapshots are immutable values");

        var linearV=v(-4,0,0);
        var linearP1=p0.add(linearV.scale(5));
        var linearP2=linearP1.add(linearV.scale(5));
        var linearTracker=new CiwsMotionTracker();
        linearTracker.observe(observer,target,provenance,101,p0,linearV,102);
        var linear2=linearTracker.observe(observer,target,provenance,106,linearP1,linearV,107).history();
        var linearAssessment=CiwsMotionPolicy.assess(linear2,.25);
        check(linearAssessment.model()==CiwsMotionPolicy.Model.LINEAR,"constant velocity remains a linear prediction");
        check(CiwsMotionPolicy.atImpact(linearAssessment,50,0,.25)==CiwsMotionPolicy.Model.LINEAR,"linear candidate remains within its real hitbox margin");
        var linear3=linearTracker.observe(observer,target,provenance,111,linearP2,linearV,112).history();
        check(CiwsMotionPolicy.assess(linear3,.25).model()==CiwsMotionPolicy.Model.LINEAR,"three zero-acceleration observations remain linear");

        var oneSample=CiwsMotionPolicy.assess(reset.history(),.25);
        check(oneSample.model()==CiwsMotionPolicy.Model.INSUFFICIENT,"one sample cannot authorize contraption prediction");
        check(CiwsMotionPolicy.atImpact(oneSample,5,0,.25)==CiwsMotionPolicy.Model.UNRELIABLE,"insufficient model cannot authorize fire");
        var lowAccelTracker=new CiwsMotionTracker();
        V3 tinyA=v(.0008,0,0), tinyV=v(2,0,0), tinyP=v(0,0,0), tinyP1=ballisticPosition(tinyP,tinyV,tinyA,5), tinyV1=v(2.004,0,0);
        lowAccelTracker.observe(observer,target,provenance,101,tinyP,tinyV,102);
        var tinyAssessment=CiwsMotionPolicy.assess(lowAccelTracker.observe(observer,target,provenance,106,tinyP1,tinyV1,107).history(),.1);
        check(CiwsMotionPolicy.atImpact(tinyAssessment,10,0,.1)==CiwsMotionPolicy.Model.LINEAR,"small observed acceleration is accepted only when its future error fits the hit margin");
        check(CiwsMotionPolicy.atImpact(tinyAssessment,10,0,.03)==CiwsMotionPolicy.Model.UNRELIABLE,"smaller hitbox rejects the same extrapolation error");
        check(CiwsMotionPolicy.atImpact(tinyAssessment,20,0,.1)==CiwsMotionPolicy.Model.UNRELIABLE,"longer flight horizon increases prediction error quadratically");
        check(CiwsMotionPolicy.atImpact(tinyAssessment,10,.07,.1)==CiwsMotionPolicy.Model.UNRELIABLE,"solver miss consumes the remaining geometric margin");

        var linearTarget=new TargetMotion(v(10,0,0),v(1,0,0),V3.ZERO);
        var acceleratedTarget=new TargetMotion(v(10,0,0),v(1,0,0),v(0.1,0,0));
        check(Math.abs(linearTarget.at(10).x()-20)<1.0e-12,"linear TargetMotion preserves p+v*t");
        check(Math.abs(acceleratedTarget.at(10).x()-25)<1.0e-12,"accelerated TargetMotion applies one half a t squared");
        check(Math.abs(acceleratedTarget.at(40).x()-125)<1.0e-12,"fixed dependency caps acceleration after its 30-tick horizon");
        var oppositeHistory=historyForOpposingAcceleration(observer,target,provenance);
        check(CiwsMotionPolicy.assess(oppositeHistory,1.0).model()==CiwsMotionPolicy.Model.UNRELIABLE,"opposing acceleration vectors are not averaged into a false stable trend");

        check(CiwsPolicy.approaching(10,0,0,-2,0,0)==5,"incoming-threat gate remains available");
        check(!CiwsPolicy.eligible(true,true,owner,null,null,null,Relation.UNKNOWN,false),"unknown owner cannot fire from motion history");
        var lock=CiwsPolicy.observe(CiwsPolicy.Lock.empty(),new CiwsPolicy.Candidate(target,100,5,10));
        lock=CiwsPolicy.observe(lock,new CiwsPolicy.Candidate(target,105,5,10));
        check(CiwsPolicy.shouldFire(lock,Long.MIN_VALUE),"normal projectile two-sweep lock policy is unchanged");

        var bounded=new CiwsMotionTracker();
        for(int i=0;i<CiwsMotionTracker.MAX_ENTRIES+1;i++)
            bounded.observe(observer,new UUID(0,i+1L),provenance,1,p0,v0,1);
        check(bounded.size()==CiwsMotionTracker.MAX_ENTRIES,"history memory has a hard entry cap");
        bounded.clearMount(observer.dimension(),observer.mountPosition());
        check(bounded.size()==0,"mount unload/disable clear removes histories");

        var cleanup=new CiwsMotionTracker();
        var mountPos=new BlockPos(4,70,4);
        var radarPos=new BlockPos(400,70,400);
        var radarObserver=new CiwsMotionTracker.Observer(observer.dimension(),mountPos.asLong(),radarPos.asLong(),owner,null);
        check(cleanup.beginObservation(radarObserver,300),"current radar source is established even before a candidate exists");
        cleanup.observe(radarObserver,target,provenance,301,p0,v0,301);
        cleanup.clearChunk(observer.dimension(),new ChunkPos(radarPos));
        check(cleanup.size()==0,"unloading the linked radar's chunk clears its temporal history");

        String coordinator=Files.readString(Path.of("src/main/java/local/cmi/weapons/CiwsCoordinator.java"));
        String trackerSource=Files.readString(Path.of("src/main/java/local/cmi/weapons/CiwsMotionTracker.java"));
        String integration=Files.readString(Path.of("src/main/java/local/cmi/CreateMilitaryIntegration.java"));
        check(coordinator.contains("radar.getTracks()")&&coordinator.contains("MOTION.observe(observer, id")&&coordinator.contains("track.scannedTime()"),"only physical radar track observations enter history");
        check(!coordinator.contains("CombatServices.contacts(")&&!coordinator.contains("getAllEntities(")&&!coordinator.contains("Sensor.scan("),"motion history does not introduce a global sensor");
        check(!trackerSource.contains("net.minecraft.world.entity.Entity")&&!trackerSource.contains("ServerLevel")&&!trackerSource.contains("CombatServices."),"history stores no entity or world references");
        check(coordinator.contains("target instanceof MunitionsPhysicsContraptionEntity")&&coordinator.contains("new TargetMotion(Ships.v3(contact.position()), Ships.v3(velocity), targetAcceleration)"),"only contraption missile path supplies measured acceleration to solver");
        check(coordinator.contains("plan.solver().approach(aim.barrel())")&&coordinator.contains("solver.path(direction, lifetime)"),"alignment uses the same solver model and path remains the cannon shell path");
        check(coordinator.contains("CiwsMotionPolicy.atImpact")&&coordinator.contains("TrajectorySafety.clear")&&coordinator.contains("CombatServices.relation")&&coordinator.contains("CombatServices.canEngage")&&coordinator.contains("radarTrackStillCurrent(binding.radar()"),"prediction requires hit-margin, final physical-track refresh, IFF and safe corridor");
        check(!coordinator.contains("CombatServices.neutralize(")&&!coordinator.contains("new AbstractCannonProjectile"),"prediction cannot synthesize or neutralize an impact");
        check(integration.contains("CiwsCoordinator.clearDimension")&&integration.contains("CiwsCoordinator.clearChunk")&&coordinator.contains("MOTION.beginObservation(observer, now)"),"dimension and mount/radar chunk unload and source changes clear temporal state");
        System.out.println("OK: CIWS physical-radar sample cadence, bounded history, acceleration confidence, uncertainty margin, fast linear CBC path and fail-closed gates");
    }

    private static CiwsMotionTracker.History historyForOpposingAcceleration(CiwsMotionTracker.Observer observer, UUID target,
            CiwsMotionTracker.Provenance provenance) {
        var tracker=new CiwsMotionTracker();
        V3 p0=v(0,0,0),v0=v(1,0,0),a1=v(.1,0,0);
        V3 p1=ballisticPosition(p0,v0,a1,5),v1=v(1.5,0,0),a2=v(-.1,0,0);
        V3 p2=ballisticPosition(p1,v1,a1,5),v2=v(1,0,0);
        tracker.observe(observer,target,provenance,101,p0,v0,102);
        tracker.observe(observer,target,provenance,106,p1,v1,107);
        return tracker.observe(observer,target,provenance,111,p2,v2,112).history();
    }

    private static CiwsMotionTracker.History historyForAcceleration(CiwsMotionTracker.Observer observer, UUID target,
            CiwsMotionTracker.Provenance provenance, V3 acceleration) {
        var tracker=new CiwsMotionTracker();
        V3 p0=v(0,0,0),v0=v(1,-1,2);
        V3 p1=ballisticPosition(p0,v0,acceleration,5),v1=v0.add(acceleration.scale(5));
        V3 p2=ballisticPosition(p1,v1,acceleration,5),v2=v1.add(acceleration.scale(5));
        tracker.observe(observer,target,provenance,201,p0,v0,202);
        tracker.observe(observer,target,provenance,206,p1,v1,207);
        return tracker.observe(observer,target,provenance,211,p2,v2,212).history();
    }
}
