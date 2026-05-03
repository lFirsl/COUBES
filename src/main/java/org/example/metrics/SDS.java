package org.example.metrics;

import org.example.metrics.BoundsCalculator.TheoreticalBounds;

/**
 * Scheduler Decision Score (SDS) — normalises actual simulation metrics
 * against theoretical bounds using min-max normalisation.
 *
 * All scores are in [0, 1] where 1 = best possible, 0 = worst possible.
 * Lower-is-better metrics (TTC, energy) are inverted after normalisation.
 * Higher-is-better metrics (consolidation) are used directly.
 */
public class SDS {

    public record Result(
            double ttcNorm,
            double energyNorm,
            double consolidationNorm,
            double score
    ) {
        @Override
        public String toString() {
            return String.format(
                    "SDS = %.4f  [ TTC=%.4f, Energy=%.4f, Consolidation=%.4f ]",
                    score, ttcNorm, energyNorm, consolidationNorm);
        }
    }

    /**
     * Compute the SDS from actual metrics and theoretical bounds.
     *
     * @param bounds        Theoretical min/max from BoundsCalculator
     * @param actualTTC     Actual simulated time-to-completion (seconds)
     * @param actualEnergy  Actual energy consumption (Wh)
     * @param actualConsolidation Actual time-weighted consolidation ratio
     * @return SDS result with per-metric scores and composite
     */
    public static Result compute(TheoreticalBounds bounds,
                                 double actualTTC, double actualEnergy,
                                 double actualConsolidation) {
        // TTC: lower is better → invert
        double ttcNorm = invertedNorm(actualTTC, bounds.minTTC(), bounds.maxTTC());

        // Energy: lower is better → invert
        double energyNorm = invertedNorm(actualEnergy, bounds.minEnergy(), bounds.maxEnergy());

        // Consolidation: higher is better → direct
        double consolNorm = directNorm(actualConsolidation,
                bounds.minConsolidation(), bounds.maxConsolidation());

        double score = (ttcNorm + energyNorm + consolNorm) / 3.0;

        return new Result(ttcNorm, energyNorm, consolNorm, score);
    }

    /** Normalise and invert (for lower-is-better metrics). Result: 1 = best, 0 = worst. */
    private static double invertedNorm(double actual, double min, double max) {
        if (max == min) return 1.0; // no range → perfect score
        double norm = (actual - min) / (max - min);
        return clamp(1.0 - norm);
    }

    /** Normalise directly (for higher-is-better metrics). Result: 1 = best, 0 = worst. */
    private static double directNorm(double actual, double min, double max) {
        if (max == min) return 1.0;
        return clamp((actual - min) / (max - min));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
