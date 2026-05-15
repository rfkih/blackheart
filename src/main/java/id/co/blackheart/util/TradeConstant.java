package id.co.blackheart.util;

import lombok.Getter;

import java.math.BigDecimal;

public final class TradeConstant {
    TradeConstant() {
    }


    public static final BigDecimal MIN_USDT_NOTIONAL = new BigDecimal("7");
    public static final BigDecimal DEFAULT_QTY_STEP = new BigDecimal("0.00001");
    public static final BigDecimal MIN_POSITION_QTY = new BigDecimal("0.0001");

    public static final BigDecimal BUY_PRICE_BUFFER = new BigDecimal("1.001");
    public static final BigDecimal SELL_PRICE_BUFFER = new BigDecimal("0.999");

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_PARTIALLY_CLOSED = "PARTIALLY_CLOSED";

    public static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    public static final String EXIT_STRUCTURE_TP1_RUNNER = "TP1_RUNNER";
    public static final String EXIT_STRUCTURE_TP1_TP2_RUNNER = "TP1_TP2_RUNNER";
    public static final String EXIT_STRUCTURE_RUNNER_ONLY = "RUNNER_ONLY";

    public static final String TARGET_ALL = "ALL";

    /**
     * Default trading-pair symbol used by call sites that have not yet been
     * parameterised on a per-account symbol. Lift to a config property when
     * multi-symbol live trading lands; until then this is the single source
     * of truth so we don't grep "BTCUSDT" through six packages.
     */
    public static final String SYMBOL_BTCUSDT = "BTCUSDT";


    @Getter
    public enum TradeType {
        LONG, SHORT
    }

    @Getter
    public enum DecisionType {
        HOLD,
        OPEN_LONG,
        OPEN_SHORT,
        CLOSE_LONG,
        CLOSE_SHORT,
        UPDATE_POSITION_MANAGEMENT
    }

    @Getter
    public enum INTERVAL{
        ONE_MINUTE("1m","1 Minutes Interval"),
        FIVE_MINUTE("5m","5 Minutes Interval"),
        FIVETEEN_MINUTE("15m","15 Minutes Interval");

        private final String code;
        private final String description;
        INTERVAL(String code,String description){
            this.code=code;
            this.description=description;
        }
    }



}
