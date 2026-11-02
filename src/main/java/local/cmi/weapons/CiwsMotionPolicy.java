package local.cmi.weapons;

import com.mrvew.firecontrol.ballistics.V3;
import java.util.List;

/** Pure confidence policy for radar-sampled contraption motion. */
public final class CiwsMotionPolicy {
    public enum Model { LINEAR, ACCELERATED, UNRELIABLE, INVALID, INSUFFICIENT }

    /** observedAcceleration is measured; acceleration is the vector actually supplied to TargetMotion. */
    public record Assessment(Model model, V3 acceleration, V3 observedAcceleration,
                             double uncertaintyAcceleration, double positionResidual,
                             int samples, long scannedTime) {}

    private CiwsMotionPolicy() {}

    /**
     * Requires consecutive physical radar sweeps. Two samples may authorize a linear model only
     * if its measured acceleration later fits inside the true hitbox margin. Three sweeps can
     * confirm an accelerated model when both acceleration change and position residual fit the
     * observed geometric tolerance.
     */
    public static Assessment assess(CiwsMotionTracker.History history, double hitTolerance) {
        if (history == null || history.target() == null || history.provenance() == null ||
                history.provenance().owner() == null || !Double.isFinite(hitTolerance) || hitTolerance <= 0) {
            return invalid(history);
        }
        List<CiwsMotionTracker.Sample> samples = history.samples();
        if (samples.isEmpty() || samples.size() > CiwsMotionTracker.MAX_SAMPLES) return invalid(history);
        for (CiwsMotionTracker.Sample sample : samples) if (sample == null || !sample.finite()) return invalid(history);
        CiwsMotionTracker.Sample latest = samples.get(samples.size() - 1);
        if (samples.size() == 1) return new Assessment(Model.INSUFFICIENT, V3.ZERO, V3.ZERO, Double.NaN, Double.NaN, 1, latest.scannedTime());

        CiwsMotionTracker.Sample previous = samples.get(samples.size() - 2);
        long delta = latest.scannedTime() - previous.scannedTime();
        if (delta != CiwsMotionTracker.SCAN_INTERVAL_TICKS) return invalid(history);
        V3 recentAcceleration = latest.velocity().sub(previous.velocity()).scale(1.0 / delta);
        double recentResidual = positionResidual(previous, latest, recentAcceleration, delta);
        if (!recentAcceleration.isFinite() || !Double.isFinite(recentResidual)) return invalid(history);
        double recentUncertainty = 2.0 * recentResidual / (delta * (double) delta);

        if (samples.size() == 2) {
            if (recentResidual > hitTolerance) return new Assessment(Model.UNRELIABLE, V3.ZERO, recentAcceleration,
                    recentUncertainty, recentResidual, 2, latest.scannedTime());
            return new Assessment(Model.LINEAR, V3.ZERO, recentAcceleration, recentUncertainty,
                    recentResidual, 2, latest.scannedTime());
        }

        CiwsMotionTracker.Sample oldest = samples.get(0);
        CiwsMotionTracker.Sample middle = previous;
        long firstDelta = middle.scannedTime() - oldest.scannedTime();
        if (firstDelta != CiwsMotionTracker.SCAN_INTERVAL_TICKS) return invalid(history);
        V3 firstAcceleration = middle.velocity().sub(oldest.velocity()).scale(1.0 / firstDelta);
        double intervalResidual = positionResidual(middle, latest, firstAcceleration, delta);
        if (!firstAcceleration.isFinite() || !Double.isFinite(intervalResidual)) return invalid(history);
        double accelerationDifference = recentAcceleration.sub(firstAcceleration).length();
        double differenceOverObservedInterval = 0.5 * accelerationDifference * delta * (double) delta;
        double uncertainty = accelerationDifference + 2.0 * intervalResidual / (delta * (double) delta);
        if (!Double.isFinite(accelerationDifference) || !Double.isFinite(differenceOverObservedInterval) || !Double.isFinite(uncertainty)) return invalid(history);
        if (intervalResidual > hitTolerance || differenceOverObservedInterval > hitTolerance) {
            return new Assessment(Model.UNRELIABLE, V3.ZERO, recentAcceleration, uncertainty,
                    intervalResidual, 3, latest.scannedTime());
        }
        Model model = recentAcceleration.lengthSqr() == 0.0 ? Model.LINEAR : Model.ACCELERATED;
        return new Assessment(model, model == Model.ACCELERATED ? recentAcceleration : V3.ZERO,
                recentAcceleration, uncertainty, intervalResidual, 3, latest.scannedTime());
    }

    /**
     * Checks the solver's actual flight horizon and remaining AABB hit margin. The quadratic
     * bound is deliberately conservative beyond TargetMotion's 30-tick acceleration horizon.
     */
    public static Model atImpact(Assessment assessment, double flightTicks, double solverMiss, double hitTolerance) {
        if (assessment == null || assessment.model() == Model.INVALID || assessment.model() == Model.INSUFFICIENT ||
                assessment.model() == Model.UNRELIABLE || !finite(flightTicks, solverMiss, hitTolerance) ||
                flightTicks <= 0 || solverMiss < 0 || hitTolerance <= 0 || solverMiss >= hitTolerance ||
                assessment.observedAcceleration() == null || !assessment.observedAcceleration().isFinite() ||
                !Double.isFinite(assessment.uncertaintyAcceleration()) || assessment.uncertaintyAcceleration() < 0) {
            return assessment != null && assessment.model() == Model.INVALID ? Model.INVALID : Model.UNRELIABLE;
        }
        double margin = hitTolerance - solverMiss;
        double measured = assessment.observedAcceleration().length();
        double accelerationError = assessment.model() == Model.ACCELERATED
                ? assessment.uncertaintyAcceleration()
                : measured + assessment.uncertaintyAcceleration();
        double projectedError = 0.5 * accelerationError * flightTicks * flightTicks;
        if (!Double.isFinite(projectedError) || projectedError >= margin) return Model.UNRELIABLE;
        return assessment.model();
    }

    private static double positionResidual(CiwsMotionTracker.Sample from, CiwsMotionTracker.Sample to,
                                           V3 acceleration, long delta) {
        double t = delta;
        V3 predicted = from.position().add(from.velocity().scale(t)).add(acceleration.scale(0.5 * t * t));
        return predicted.isFinite() ? predicted.sub(to.position()).length() : Double.NaN;
    }

    private static Assessment invalid(CiwsMotionTracker.History history) {
        long scanned = history == null || history.latest() == null ? Long.MIN_VALUE : history.latest().scannedTime();
        int samples = history == null ? 0 : history.samples().size();
        return new Assessment(Model.INVALID, V3.ZERO, V3.ZERO, Double.NaN, Double.NaN, samples, scanned);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
