package util;

/**
 * PositiveEV utility helpers used by Simulator and Arbitrage.
 * - American odds conversions
 * - Two-way fair (de-vig) probabilities
 * - Expected value calculations
 *
 * All methods are static and null-safe (return NaN if inputs are null/invalid).
 */
public final class PositiveEV {
    private PositiveEV() {}

    /** Raw implied probability from American odds (with vig). */
    public static double impliedProbRaw(Double american) {
        if (american == null || american.isNaN() || american.isInfinite()) return Double.NaN;
        double a = american.doubleValue();
        if (a > 0.0) return 100.0 / (a + 100.0);
        if (a < 0.0) return (-a) / ((-a) + 100.0);
        return Double.NaN; // odds of 0 are invalid
    }

    /** American → Decimal odds. */
    public static double decimalOdds(Double american) {
        if (american == null || american.isNaN() || american.isInfinite()) return Double.NaN;
        double a = american.doubleValue();
        if (a > 0.0) return 1.0 + (a / 100.0);
        if (a < 0.0) return 1.0 + (100.0 / (-a));
        return Double.NaN;
    }

    /**
     * De-vig two-way market from American prices.
     * Returns {p1, p2} such that p1+p2 = 1 (if inputs valid). Otherwise {NaN, NaN}.
     */
    public static double[] fairTwoWay(Double american1, Double american2) {
        double p1raw = impliedProbRaw(american1);
        double p2raw = impliedProbRaw(american2);
        if (!isFinite(p1raw) || !isFinite(p2raw)) return new double[]{Double.NaN, Double.NaN};
        double z = p1raw + p2raw;
        if (z <= 0.0 || !isFinite(z)) return new double[]{Double.NaN, Double.NaN};
        return new double[]{ p1raw / z, p2raw / z };
    }

    /**
     * Expected value per $1 stake using win prob pWin and American odds.
     * EV = pWin * (decimal - 1) - (1 - pWin)
     */
    public static double expectedValue(double pWin, Double american) {
        double d = decimalOdds(american);
        if (!isFinite(pWin) || !isFinite(d)) return Double.NaN;
        return pWin * (d - 1.0) - (1.0 - pWin);
    }

    /**
     * Convenience for spread helpers that only have one side’s sharp price:
     * Uses sharp American to estimate pWin (with vig), then computes EV at soft American.
     * Prefer fairTwoWay(...) when you have both sides — this is a fallback.
     */
    public static double calcEV(Double softAmerican, Double sharpAmerican) {
        double p = impliedProbRaw(sharpAmerican);
        if (!isFinite(p)) return Double.NaN;
        return expectedValue(p, softAmerican);
    }

    // ---------- internals ----------
    private static boolean isFinite(double x) { return !Double.isNaN(x) && !Double.isInfinite(x); }
}
