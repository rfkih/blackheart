package id.co.blackheart.engine;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.RegimeSnapshot;
import id.co.blackheart.dto.strategy.RiskSnapshot;
import id.co.blackheart.dto.strategy.VolatilitySnapshot;
import id.co.blackheart.model.FeatureStore;

import java.math.BigDecimal;

/**
 * Pure helpers shared by spec-driven engines. Each method derives a single
 * scalar from a context snapshot; behaviour is bit-identical to the inline
 * copies that previously lived in each engine.
 */
public final class EngineContextHelpers {

    private EngineContextHelpers() {
    }

    public static boolean isMarketVetoed(EnrichedStrategyContext ctx) {
        return ctx.getMarketQualitySnapshot() != null
                && Boolean.FALSE.equals(ctx.getMarketQualitySnapshot().getTradable());
    }

    public static BigDecimal resolveAtr(FeatureStore f) {
        if (f == null || f.getAtr() == null) return null;
        return f.getAtr().compareTo(BigDecimal.ZERO) > 0 ? f.getAtr() : null;
    }

    public static BigDecimal resolveRegimeScore(EnrichedStrategyContext ctx) {
        RegimeSnapshot r = ctx.getRegimeSnapshot();
        return (r != null && r.getTrendScore() != null) ? r.getTrendScore() : BigDecimal.ZERO;
    }

    public static BigDecimal resolveJumpRisk(EnrichedStrategyContext ctx) {
        VolatilitySnapshot v = ctx.getVolatilitySnapshot();
        return (v != null && v.getJumpRiskScore() != null) ? v.getJumpRiskScore() : BigDecimal.ZERO;
    }

    public static BigDecimal resolveRiskMultiplier(EnrichedStrategyContext ctx) {
        RiskSnapshot r = ctx.getRiskSnapshot();
        return (r != null && r.getRiskMultiplier() != null) ? r.getRiskMultiplier() : BigDecimal.ONE;
    }
}
