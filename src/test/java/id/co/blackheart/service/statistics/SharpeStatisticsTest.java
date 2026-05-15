package id.co.blackheart.service.statistics;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Numerical smoke tests for the PSR / DSR / moment utilities. The values
 * cross-check against textbook closed-form results — if any of these drift,
 * downstream PSR display will silently lie.
 */
class SharpeStatisticsTest {

    private static final double TOL = 1e-3;

    // ── StandardNormal ───────────────────────────────────────────────────────

    @Test
    void normalCdfMatchesKnownPercentiles() {
        assertEquals(0.5, StandardNormal.cdf(0.0), 1e-6);
        assertEquals(0.8413, StandardNormal.cdf(1.0), TOL);   // 1σ
        assertEquals(0.9772, StandardNormal.cdf(2.0), TOL);   // 2σ
        assertEquals(0.0228, StandardNormal.cdf(-2.0), TOL);
    }

    @Test
    void inverseCdfRoundTrips() {
        for (double p : new double[]{0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 0.999}) {
            double x = StandardNormal.inverseCdf(p);
            assertEquals(p, StandardNormal.cdf(x), 1e-6,
                    "Φ(Φ^-1(" + p + ")) should round-trip");
        }
    }

    @Test
    void inverseCdfMatchesKnownQuantiles() {
        assertEquals(1.6449, StandardNormal.inverseCdf(0.95), TOL);
        assertEquals(1.96, StandardNormal.inverseCdf(0.975), TOL);
        assertEquals(0.0, StandardNormal.inverseCdf(0.5), 1e-9);
    }

    // ── Moments ──────────────────────────────────────────────────────────────

    @Test
    void normalDataHasZeroSkewAndKurtosisNearThree() {
        double[] xs = generateGaussian(5_000, 0.0, 1.0, 42L);
        assertEquals(0.0, SharpeStatistics.skewness(xs), 0.1);
        assertEquals(3.0, SharpeStatistics.kurtosis(xs), 0.2);
    }

    @Test
    void positivelySkewedDataHasPositiveSkew() {
        // Squared standard normal — chi-squared(1), strongly right-skewed.
        double[] z = generateGaussian(5_000, 0.0, 1.0, 7L);
        double[] xs = new double[z.length];
        for (int i = 0; i < z.length; i++) xs[i] = z[i] * z[i];
        assertTrue(SharpeStatistics.skewness(xs) > 1.5,
                "chi-squared(1) skew should exceed 1.5");
    }

    @Test
    void zeroVarianceCornerCases() {
        double[] flat = {1.0, 1.0, 1.0, 1.0};
        assertEquals(0.0, SharpeStatistics.stddev(flat), 1e-12);
        assertEquals(0.0, SharpeStatistics.skewness(flat), 1e-12);
        // Kurtosis falls back to the normal default when variance is zero —
        // this keeps PSR's denominator finite on degenerate samples.
        assertEquals(3.0, SharpeStatistics.kurtosis(flat), 1e-12);
    }

    // ── PSR ──────────────────────────────────────────────────────────────────

    @Test
    void psrIsHalfWhenObservedEqualsBenchmark() {
        // When SR_obs == SR*, the z-score is 0 and Φ(0) = 0.5 — undetectable.
        double psr = SharpeStatistics.psr(0.0, 0.0, 100, 0.0, 3.0);
        assertEquals(0.5, psr, 1e-6);
    }

    @Test
    void psrApproachesOneAsSampleSizeGrowsAndEdgeIsReal() {
        // SR=0.2 per period, normal returns. With 1000 periods we should be
        // very confident the true SR > 0.
        double psrSmall = SharpeStatistics.psr(0.2, 0.0, 30, 0.0, 3.0);
        double psrLarge = SharpeStatistics.psr(0.2, 0.0, 1000, 0.0, 3.0);
        assertTrue(psrLarge > psrSmall, "more samples → more confidence");
        assertTrue(psrLarge > 0.99, "1000-sample PSR for sr=0.2 should exceed 0.99");
    }

    @Test
    void psrHurtBySkewAndKurtosis() {
        // Negative skew + heavy tails widen the SR estimator's variance →
        // lower PSR for the same observed Sharpe. This is exactly the use
        // case the metric was designed for: penalising "Sharpe with rare
        // crash risk" vs "Sharpe from clean Gaussian returns".
        double normal = SharpeStatistics.psr(0.15, 0.0, 200, 0.0, 3.0);
        double skewed = SharpeStatistics.psr(0.15, 0.0, 200, -1.5, 8.0);
        assertTrue(normal > skewed,
                "fat-tailed/skewed returns should yield lower PSR");
    }

    // ── Expected max + DSR ───────────────────────────────────────────────────

    @Test
    void expectedMaxSharpeGrowsWithTrials() {
        double sigma = 0.1;
        double e10 = SharpeStatistics.expectedMaxSharpe(sigma, 10);
        double e1000 = SharpeStatistics.expectedMaxSharpe(sigma, 1000);
        assertTrue(e1000 > e10, "more trials → higher expected max under null");
        // Loose sanity: max of 1000 ~N(0, σ) trials is ~3σ.
        assertTrue(e1000 > 2.5 * sigma && e1000 < 3.5 * sigma,
                "E[max] of 1000 N(0,σ) trials should be roughly 3σ, got " + e1000);
    }

    @Test
    void dsrPenalisesMultipleTrialsVersusSingleTrialPsr() {
        // Fake sweep of 500 Gaussian Sharpes. PSR vs zero on the top
        // observed value would call it a great strategy; DSR should land
        // near 0.5 because selection bias eats most of the apparent edge.
        double[] sharpes = generateGaussian(500, 0.0, 0.15, 9L);
        double topSharpe = max(sharpes);

        double psrVsZero = SharpeStatistics.psr(topSharpe, 0.0, 100, 0.0, 3.0);
        double dsr = SharpeStatistics.dsr(topSharpe, sharpes, 100, 0.0, 3.0);

        assertTrue(psrVsZero > 0.95, "naive PSR overstates the edge: " + psrVsZero);
        assertTrue(dsr < 0.7,
                "DSR should discount the top-of-N selection effect, got " + dsr);
    }

    @Test
    void dsrReturnsNanForTooFewTrials() {
        double dsr = SharpeStatistics.dsr(0.5, new double[]{0.5}, 100, 0.0, 3.0);
        assertTrue(Double.isNaN(dsr));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static double[] generateGaussian(int n, double mean, double sd, long seed) {
        Random rng = new Random(seed);
        double[] xs = new double[n];
        for (int i = 0; i < n; i++) xs[i] = mean + sd * rng.nextGaussian();
        return xs;
    }

    private static double max(double[] xs) {
        double m = xs[0];
        for (double x : xs) if (x > m) m = x;
        return m;
    }
}
