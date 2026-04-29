package id.co.blackheart.service.statistics;

/**
 * Standard normal CDF and inverse CDF. Inlined here to avoid pulling in
 * Apache Commons Math just for two functions used by PSR/DSR.
 *
 * <p>Both implementations target ~1e-7 relative error, which is plenty for
 * statistical reporting (the Sharpe estimator's own uncertainty is many
 * orders of magnitude looser than that).
 */
public final class StandardNormal {

    private static final double SQRT_2 = Math.sqrt(2.0);

    private StandardNormal() {}

    /** Φ(x) — standard normal cumulative distribution function. */
    public static double cdf(double x) {
        // Φ(x) = 0.5 * (1 + erf(x / sqrt(2))), and Java's Math doesn't expose
        // erf either — Abramowitz & Stegun 7.1.26 gives ~1.5e-7 max abs error.
        return 0.5 * (1.0 + erf(x / SQRT_2));
    }

    /** Φ^-1(p) — standard normal quantile / inverse CDF. p ∈ (0, 1). */
    public static double inverseCdf(double p) {
        if (p <= 0.0 || p >= 1.0) {
            if (p == 0.0) return Double.NEGATIVE_INFINITY;
            if (p == 1.0) return Double.POSITIVE_INFINITY;
            throw new IllegalArgumentException("p must be in (0, 1), got " + p);
        }
        // Peter Acklam's algorithm — relative error < 1.15e-9.
        // https://web.archive.org/web/20150910044504/http://home.online.no/~pjacklam/notes/invnorm/
        final double[] a = {
                -3.969683028665376e+01,
                 2.209460984245205e+02,
                -2.759285104469687e+02,
                 1.383577518672690e+02,
                -3.066479806614716e+01,
                 2.506628277459239e+00
        };
        final double[] b = {
                -5.447609879822406e+01,
                 1.615858368580409e+02,
                -1.556989798598866e+02,
                 6.680131188771972e+01,
                -1.328068155288572e+01
        };
        final double[] c = {
                -7.784894002430293e-03,
                -3.223964580411365e-01,
                -2.400758277161838e+00,
                -2.549732539343734e+00,
                 4.374664141464968e+00,
                 2.938163982698783e+00
        };
        final double[] d = {
                 7.784695709041462e-03,
                 3.224671290700398e-01,
                 2.445134137142996e+00,
                 3.754408661907416e+00
        };
        final double pLow = 0.02425;
        final double pHigh = 1 - pLow;

        double q;
        double r;
        if (p < pLow) {
            q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0]*q + c[1])*q + c[2])*q + c[3])*q + c[4])*q + c[5]) /
                   ((((d[0]*q + d[1])*q + d[2])*q + d[3])*q + 1);
        }
        if (p <= pHigh) {
            q = p - 0.5;
            r = q * q;
            return (((((a[0]*r + a[1])*r + a[2])*r + a[3])*r + a[4])*r + a[5]) * q /
                   (((((b[0]*r + b[1])*r + b[2])*r + b[3])*r + b[4])*r + 1);
        }
        q = Math.sqrt(-2 * Math.log(1 - p));
        return -(((((c[0]*q + c[1])*q + c[2])*q + c[3])*q + c[4])*q + c[5]) /
                ((((d[0]*q + d[1])*q + d[2])*q + d[3])*q + 1);
    }

    private static double erf(double x) {
        // Abramowitz & Stegun 7.1.26 — max abs error 1.5e-7 for x >= 0.
        double sign = Math.signum(x);
        double absX = Math.abs(x);

        double t = 1.0 / (1.0 + 0.3275911 * absX);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t)
                + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
                * Math.exp(-absX * absX);
        return sign * y;
    }
}
