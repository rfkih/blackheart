package id.co.blackheart.service.risk;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.StrategyDailyRealizedCurve;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.StrategyDailyRealizedCurveRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.alert.AlertService;
import id.co.blackheart.service.alert.AlertSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Concentration and correlation guard. Evaluated inside {@link RiskGuardService#canOpen}
 * after the regime gate so it only fires for OPEN_LONG / OPEN_SHORT decisions.
 *
 * <p><b>Concentration check</b> — sums {@code capital_allocation_pct} across all
 * same-direction open strategies on the account. If adding the requesting strategy's
 * allocation would breach {@code account.max_capital_concentration_pct}, the entry is
 * blocked. Protects against inadvertent book-level over-sizing when multiple strategies
 * fire together.
 *
 * <p><b>Correlation check</b> — computes Pearson correlation on the last 30 days of
 * daily realized P&L ({@code strategy_daily_realized_curve.daily_realized_pnl_amount})
 * between the requesting strategy and each other same-side open strategy. If any pair
 * exceeds {@code account.max_corr_block_threshold}, the entry is blocked. Null
 * thresholds disable each check independently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorrelationGuardService {

    private static final int CORR_WINDOW_DAYS = 30;
    private static final int MIN_CORR_DAYS    = 10;

    private final TradesRepository tradesRepository;
    private final AccountStrategyRepository accountStrategyRepository;
    private final StrategyDailyRealizedCurveRepository curveRepository;
    private final AlertService alertService;

    public record ConcentrationVerdict(boolean allowed, String reason) {
        static ConcentrationVerdict allow() { return new ConcentrationVerdict(true, null); }
        static ConcentrationVerdict deny(String reason) { return new ConcentrationVerdict(false, reason); }
    }

    public ConcentrationVerdict check(AccountStrategy requestingStrategy, Account account, String side) {
        // Collect accountStrategyIds of OTHER strategies with open same-side trades.
        List<Trades> openTrades = tradesRepository.findOpenByAccountIds(List.of(account.getAccountId()));
        List<UUID> sameSideStrategyIds = openTrades.stream()
                .filter(t -> side.equalsIgnoreCase(t.getSide()))
                .map(Trades::getAccountStrategyId)
                .filter(Objects::nonNull)   // guard against legacy rows with null account_strategy_id
                .distinct()
                .filter(id -> !id.equals(requestingStrategy.getAccountStrategyId()))
                .collect(Collectors.toList());

        if (sameSideStrategyIds.isEmpty()) {
            return ConcentrationVerdict.allow();
        }

        // --- Concentration check ---
        if (account.getMaxCapitalConcentrationPct() != null) {
            List<AccountStrategy> openStrategies = accountStrategyRepository.findAllById(sameSideStrategyIds);
            BigDecimal existingAlloc = openStrategies.stream()
                    .map(as -> as.getCapitalAllocationPct() != null ? as.getCapitalAllocationPct() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal thisAlloc = requestingStrategy.getCapitalAllocationPct() != null
                    ? requestingStrategy.getCapitalAllocationPct() : BigDecimal.ZERO;
            BigDecimal totalAlloc = existingAlloc.add(thisAlloc);

            if (totalAlloc.compareTo(account.getMaxCapitalConcentrationPct()) > 0) {
                String reason = String.format(
                        "Capital concentration blocked: adding %s%% would bring same-%s exposure to %s%% " +
                        "(limit %s%%, accountId=%s)",
                        thisAlloc.setScale(2, RoundingMode.HALF_UP),
                        side.toUpperCase(),
                        totalAlloc.setScale(2, RoundingMode.HALF_UP),
                        account.getMaxCapitalConcentrationPct(),
                        account.getAccountId());
                log.warn("[CorrelationGuard] {}", reason);
                alertService.raise(AlertSeverity.WARN, "CONCENTRATION_BLOCKED", reason,
                        "concentration_" + account.getAccountId() + "_" + side.toUpperCase());
                return ConcentrationVerdict.deny(reason);
            }
        }

        // --- Correlation check ---
        if (account.getMaxCorrBlockThreshold() != null) {
            LocalDate endDate   = LocalDate.now().minusDays(1);
            LocalDate startDate = endDate.minusDays(CORR_WINDOW_DAYS);

            List<StrategyDailyRealizedCurve> thisCurve = curveRepository
                    .findByAccountStrategyIdAndCurveDateBetween(
                            requestingStrategy.getAccountStrategyId(), startDate, endDate);

            if (thisCurve.size() < MIN_CORR_DAYS) {
                return ConcentrationVerdict.allow();
            }

            // (a, b) -> a: guard against duplicate curve_date rows caused by data anomalies;
            // DB unique constraint prevents this in normal operation.
            Map<LocalDate, Double> thisMap = thisCurve.stream().collect(Collectors.toMap(
                    StrategyDailyRealizedCurve::getCurveDate,
                    c -> c.getDailyRealizedPnlAmount().doubleValue(),
                    (a, b) -> a));

            for (UUID otherId : sameSideStrategyIds) {
                List<StrategyDailyRealizedCurve> otherCurve = curveRepository
                        .findByAccountStrategyIdAndCurveDateBetween(otherId, startDate, endDate);

                Map<LocalDate, Double> otherMap = otherCurve.stream().collect(Collectors.toMap(
                        StrategyDailyRealizedCurve::getCurveDate,
                        c -> c.getDailyRealizedPnlAmount().doubleValue(),
                        (a, b) -> a));

                List<LocalDate> common = thisMap.keySet().stream()
                        .filter(otherMap::containsKey)
                        .sorted()
                        .collect(Collectors.toList());

                if (common.size() < MIN_CORR_DAYS) continue;

                double[] xs = common.stream().mapToDouble(thisMap::get).toArray();
                double[] ys = common.stream().mapToDouble(otherMap::get).toArray();
                double r    = pearson(xs, ys);

                double threshold = account.getMaxCorrBlockThreshold().doubleValue();
                if (r >= threshold) {
                    String reason = String.format(
                            "Correlated strategies blocked: %s vs %s has 30-day P&L correlation r=%.3f >= threshold %.3f " +
                            "(side=%s, accountId=%s)",
                            requestingStrategy.getAccountStrategyId(), otherId, r, threshold,
                            side.toUpperCase(), account.getAccountId());
                    log.warn("[CorrelationGuard] {}", reason);
                    alertService.raise(AlertSeverity.WARN, "CORRELATION_BLOCKED", reason,
                            "corr_" + requestingStrategy.getAccountStrategyId() + "_" + otherId);
                    return ConcentrationVerdict.deny(reason);
                }
            }
        }

        return ConcentrationVerdict.allow();
    }

    /** Pearson product-moment correlation. Returns 0 when variance is zero or n < 3. */
    static double pearson(double[] xs, double[] ys) {
        int n = xs.length;
        if (n < 3) return 0.0;
        double meanX = Arrays.stream(xs).average().orElse(0.0);
        double meanY = Arrays.stream(ys).average().orElse(0.0);
        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = xs[i] - meanX;
            double dy = ys[i] - meanY;
            cov  += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        if (varX == 0 || varY == 0) return 0.0;
        return cov / Math.sqrt(varX * varY);
    }
}
