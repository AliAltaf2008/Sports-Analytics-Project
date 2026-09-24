package util;


public final class PositiveEV {
    private PositiveEV() {}

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


    public static double[] fairTwoWay(Double american1, Double american2) {
        double p1raw = impliedProbRaw(american1);
        double p2raw = impliedProbRaw(american2);
        if (!isFinite(p1raw) || !isFinite(p2raw)) return new double[]{Double.NaN, Double.NaN};
        double z = p1raw + p2raw;
        if (z <= 0.0 || !isFinite(z)) return new double[]{Double.NaN, Double.NaN};
        return new double[]{ p1raw / z, p2raw / z };
    }


    public static double expectedValue(double pWin, Double american) {
        double d = decimalOdds(american);
        if (!isFinite(pWin) || !isFinite(d)) return Double.NaN;
        return pWin * (d - 1.0) - (1.0 - pWin);
    }

  
    public static double calcEV(Double softAmerican, Double sharpAmerican) {
        double p = impliedProbRaw(sharpAmerican);
        if (!isFinite(p)) return Double.NaN;
        return expectedValue(p, softAmerican);
    }

    // ---------- internals ----------
    private static boolean isFinite(double x) { return !Double.isNaN(x) && !Double.isInfinite(x); }
}
