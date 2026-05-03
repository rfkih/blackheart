package id.co.blackheart.service.risk;

import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.service.alert.AlertService;
import id.co.blackheart.service.alert.AlertSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Regime-aware entry gate. When {@code account_strategy.regime_gate_enabled = true},
 * a new OPEN_LONG / OPEN_SHORT entry is permitted only if the current bar's
 * {@code trend_regime} and {@code volatility_regime} are each in the strategy's
 * configured allowed sets. Null allowed sets mean "any value passes".
 *
 * <p>This guard is evaluated inside {@link RiskGuardService#canOpen} after the
 * drawdown and concurrent-direction checks, so it never fires on CLOSE_* decisions.
 *
 * <p>Regime values are operator-controlled strings stored on {@code account_strategy}
 * (e.g. allowed_trend_regimes = "BULL,NEUTRAL"). Updating config takes effect on
 * the next candle without a redeploy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegimeGuardService {

    private final AlertService alertService;

    public record RegimeVerdict(boolean allowed, String reason) {
        static RegimeVerdict allow() { return new RegimeVerdict(true, null); }
        static RegimeVerdict deny(String reason) { return new RegimeVerdict(false, reason); }
    }

    /**
     * Returns {@code allow()} when the regime gate is disabled, featureStore is null,
     * or both regime dimensions pass. Returns {@code deny(reason)} otherwise and
     * raises an INFO alert so suppressed entries are visible in the admin inbox.
     */
    public RegimeVerdict check(AccountStrategy strategy, FeatureStore featureStore) {
        if (!Boolean.TRUE.equals(strategy.getRegimeGateEnabled())) {
            return RegimeVerdict.allow();
        }
        if (featureStore == null) {
            log.warn("[RegimeGuard] Gate enabled but no FeatureStore data for symbol={} interval={} — failing open. " +
                    "Run feature-store backfill to activate regime filtering (accountStrategyId={})",
                    strategy.getSymbol(), strategy.getIntervalName(), strategy.getAccountStrategyId());
            return RegimeVerdict.allow();
        }

        RegimeVerdict trendVerdict = checkDimension(
                "trend_regime", featureStore.getTrendRegime(),
                strategy.getAllowedTrendRegimes(), strategy);
        if (!trendVerdict.allowed()) return trendVerdict;

        return checkDimension(
                "volatility_regime", featureStore.getVolatilityRegime(),
                strategy.getAllowedVolatilityRegimes(), strategy);
    }

    private RegimeVerdict checkDimension(
            String dimensionName,
            String currentValue,
            String allowedCsv,
            AccountStrategy strategy) {

        if (allowedCsv == null || allowedCsv.isBlank()) return RegimeVerdict.allow();
        if (currentValue == null || currentValue.isBlank()) return RegimeVerdict.allow();

        Set<String> allowed = Arrays.stream(allowedCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());

        if (allowed.contains(currentValue.toUpperCase())) return RegimeVerdict.allow();

        String reason = String.format(
                "Regime gate blocked: %s='%s' not in allowed set %s (accountStrategyId=%s)",
                dimensionName, currentValue, allowed, strategy.getAccountStrategyId());
        log.info("[RegimeGuard] {}", reason);
        alertService.raise(
                AlertSeverity.INFO,
                "REGIME_GATE_BLOCKED",
                reason,
                "regime_gate_" + strategy.getAccountStrategyId());
        return RegimeVerdict.deny(reason);
    }
}
