package id.co.blackheart.service.statistics;

/**
 * Probabilistic Sharpe Ratio (PSR) and Deflated Sharpe Ratio (DSR), per
 * Bailey & López de Prado, "The Sharpe Ratio Efficient Frontier" (2012)
 * and "The Deflated Sharpe Ratio" (2014).
 *
 * <p>Both metrics answer questions the raw Sharpe cannot:
 *
 * <ul>
 *   <li><b>PSR(SR*)</b> — given an observed per-period Sharpe over N
 *   non-Gaussian returns, what is the probability that the strategy's
 *   <i>true</i> Sharpe exceeds the benchmark {@code SR*}? Default benchmark
 *   is 0 (is the edge real, accounting for skew/kurt and sample size).</li>
 *
 *   <li><b>DSR</b> — same question, but with {@code SR*} replaced by the
 *   <i>expected maximum</i> Sharpe one would observe across N independent
 *   trials of a strategy with no edge. This is the only honest way to score
 *   the leader of a parameter sweep: a top Sharpe of 2.0 across 500 random
 *   combos is much weaker evidence than a Sharpe of 2.0 from a single
 *   pre-registered run.</li>
 * </ul>
 *
 * <p>Both functions take the <i>per-period</i> Sharpe (i.e. mean / stddev of
 * the return series, NOT annualized). Annualization is a unit transform
 * applied for display; the statistical machinery here works on raw periods.
 */
public final class SharpeStatistics {

    /** Euler–Mascheroni constant — used in the expected-max-Sharpe formula. */
    private static final double EULER_MASCHERONI = 0.5772156649015329;

    private SharpeStatistics() {}

    /**
     * PSR(SR*). Returns the probability that the true Sharpe exceeds
     * {@code srBenchmark}, given an observed per-period Sharpe over a sample
     * of {@code n} returns with the supplied skewness and kurtosis (4th
     * standardized moment, NOT excess — normal returns have kurtosis = 3).
     *
     * @return value in [0, 1]; close to 1 means high confidence the edge is
     *         real, close to 0.5 means undetectable, close to 0 means the
     *         observed Sharpe is consistent with being below the benchmark.
     */
    public static double psr(double srObservedPerPeriod,
                             double srBenchmark,
                             int n,
                             double skewness,
                             double kurtosis) {
        if (n < 2) return Double.NaN;
        double sr = srObservedPerPeriod;
        // Variance of the SR estimator under non-normal IID returns
        // (Mertens 2002, eq. 2). For normal returns this collapses to
        // (1 + sr^2/2) / (n-1), matching textbook Sharpe SE.
        double srVar = 1.0 - skewness * sr + (kurtosis - 1.0) / 4.0 * sr * sr;
        if (srVar <= 0.0) return Double.NaN;
        double z = (sr - srBenchmark) * Math.sqrt((double) n - 1) / Math.sqrt(srVar);
        return StandardNormal.cdf(z);
    }

    /**
     * Expected maximum Sharpe under N independent trials of a null strategy
     * (true SR = 0), assuming the SR estimates are approximately normal with
     * standard deviation {@code sigmaSr}. Closed-form approximation from
     * Bailey & López de Prado (2014).
     */
    public static double expectedMaxSharpe(double sigmaSr, int trials) {
        if (trials <= 1 || sigmaSr <= 0.0) return 0.0;
        double n = trials;
        double term1 = (1.0 - EULER_MASCHERONI) * StandardNormal.inverseCdf(1.0 - 1.0 / n);
        double term2 = EULER_MASCHERONI * StandardNormal.inverseCdf(1.0 - 1.0 / (n * Math.E));
        return sigmaSr * (term1 + term2);
    }

    /**
     * DSR — PSR with the benchmark set to the expected max-Sharpe under N
     * trials. {@code sharpesAcrossTrials} should contain every per-period
     * Sharpe in the trial cohort (e.g. all combos in a sweep). Pass the
     * combo of interest as {@code srObservedPerPeriod}.
     */
    public static double dsr(double srObservedPerPeriod,
                             double[] sharpesAcrossTrials,
                             int n,
                             double skewness,
                             double kurtosis) {
        if (sharpesAcrossTrials == null || sharpesAcrossTrials.length < 2) return Double.NaN;
        double sigmaSr = stddev(sharpesAcrossTrials);
        double srStar = expectedMaxSharpe(sigmaSr, sharpesAcrossTrials.length);
        return psr(srObservedPerPeriod, srStar, n, skewness, kurtosis);
    }

    /** Sample skewness (third standardized moment). Returns 0 for n &lt; 3. */
    public static double skewness(double[] xs) {
        if (xs == null || xs.length < 3) return 0.0;
        double mean = mean(xs);
        double sq = 0.0;
        double cb = 0.0;
        for (double x : xs) {
            double d = x - mean;
            sq += d * d;
            cb += d * d * d;
        }
        double n = xs.length;
        double variance = sq / n;
        if (variance == 0.0) return 0.0;
        double sd = Math.sqrt(variance);
        return (cb / n) / (sd * sd * sd);
    }

    /**
     * Sample kurtosis (fourth standardized moment, NOT excess). Normal
     * returns have kurtosis 3. Returns 3 for n &lt; 4 — the normal default
     * keeps PSR's variance term well-defined when sample is too small.
     */
    public static double kurtosis(double[] xs) {
        if (xs == null || xs.length < 4) return 3.0;
        double mean = mean(xs);
        double sq = 0.0;
        double q4 = 0.0;
        for (double x : xs) {
            double d = x - mean;
            double d2 = d * d;
            sq += d2;
            q4 += d2 * d2;
        }
        double n = xs.length;
        double variance = sq / n;
        if (variance == 0.0) return 3.0;
        return (q4 / n) / (variance * variance);
    }

    public static double mean(double[] xs) {
        if (xs == null || xs.length == 0) return 0.0;
        double sum = 0.0;
        for (double x : xs) sum += x;
        return sum / xs.length;
    }

    public static double stddev(double[] xs) {
        if (xs == null || xs.length < 2) return 0.0;
        double mean = mean(xs);
        double sq = 0.0;
        for (double x : xs) {
            double d = x - mean;
            sq += d * d;
        }
        // Sample stddev (n-1) — appropriate when the samples are themselves
        // estimates (Sharpes across trials).
        return Math.sqrt(sq / (xs.length - 1));
    }
}
