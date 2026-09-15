package util;

/** Simple bankroll engine with configurable sizing. */
public final class Bankroll {

    public enum Mode { FLAT_PCT, KELLY }

    private double bankroll;           // current USD
    private final double minStake;     // floor to avoid dust bets
    private final double maxStakePct;  // hard cap as % of bankroll (e.g., 0.02 = 2%)
    private final Mode mode;
    private final double flatPct;      // for FLAT_PCT (e.g., 0.01 = 1%)
    private final double kellyFrac;    // fraction of Kelly (e.g., 0.5 = half Kelly)

    public Bankroll(double startingBankroll,
                    Mode mode,
                    double flatPct,
                    double kellyFrac,
                    double maxStakePct,
                    double minStake) {
        this.bankroll = startingBankroll;
        this.mode = mode;
        this.flatPct = flatPct;
        this.kellyFrac = kellyFrac;
        this.maxStakePct = maxStakePct;
        this.minStake = minStake;
    }

    public double getBankroll() { return bankroll; }

    /** Stake in USD given pWin and decimal odds. */
    public double sizeStake(double pWin, double decimalOdds) {
        if (bankroll <= 0) return 0.0;
        double stake;
        if (mode == Mode.FLAT_PCT) {
            stake = bankroll * flatPct;
        } else {
            // Kelly: k* = (b*p - q)/b  where b = dec-1, q = 1-p
            double b = Math.max(decimalOdds - 1.0, 1e-12);
            double p = Math.max(0.0, Math.min(1.0, pWin));
            double q = 1.0 - p;
            double k = (b * p - q) / b;
            double kAdj = Math.max(0.0, k * kellyFrac); // don't bet negative Kelly
            stake = bankroll * kAdj;
        }
        double cap = bankroll * maxStakePct;
        stake = Math.min(stake, cap);
        if (stake < minStake) stake = 0.0;
        return stake;
    }

    /** Apply win/loss; returns profit in USD (positive on win). */
    public double settle(double stake, boolean win, double decimalOdds) {
        if (stake <= 0) return 0.0;
        double profit = win ? stake * (decimalOdds - 1.0) : -stake;
        bankroll += profit;
        return profit;
    }
}
