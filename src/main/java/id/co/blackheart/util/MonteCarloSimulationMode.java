package id.co.blackheart.util;

public enum MonteCarloSimulationMode {

    /**
     * Preserve each trade's realized P&L, reshuffle execution order per simulation path.
     * Tests path-dependency and drawdown sensitivity to trade sequencing.
     */
    TRADE_SEQUENCE_SHUFFLE,

    /**
     * Sample historical trade returns with replacement.
     * Each simulation path draws N trades from the empirical return distribution.
     * Suitable for forward-projection robustness analysis.
     */
    BOOTSTRAP_RETURNS
}