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

    public GateVerdict check(AccountStrategy requestingStrategy, Account account, String side) {
        List<UUID> sameSideStrategyIds = collectSameSideOpenStrategyIds(
                account, side, requestingStrategy.getAccountStrategyId());
        if (sameSideStrategyIds.isEmpty()) {
            return GateVerdict.allow();
        }

        GateVerdict concentration = checkConcentration(requestingStrategy, account, side, sameSideStrategyIds);
        if (!concentration.allowed()) return concentration;

        return checkCorrelation(requestingStrategy, account, side, sameSideStrategyIds);
    }

    /** Collect accountStrategyIds of OTHER strategies with open same-side trades. */
    private List<UUID> collectSameSideOpenStrategyIds(Account account, String side, UUID requestingId) {
        List<Trades> openTrades = tradesRepository.findOpenByAccountIds(List.of(account.getAccountId()));
        return openTrades.stream()
                .filter(t -> side.equalsIgnoreCase(t.getSide()))
                .map(Trades::getAccountStrategyId)
                .filter(Objects::nonNull)   // guard against legacy rows with null account_strategy_id
                .distinct()
                .filter(id -> !id.equals(requestingId))
                .toList();
    }

    private GateVerdict checkConcentration(
            AccountStrategy requestingStrategy, Account account, String side, List<UUID> sameSideStrategyIds) {
        if (account.getMaxCapitalConcentrationPct() == null) return GateVerdict.allow();

        List<AccountStrategy> openStrategies = accountStrategyRepository.findAllById(sameSideStrategyIds);
        BigDecimal existingAlloc = openStrategies.stream()
                .map(as -> as.getCapitalAllocationPct() != null ? as.getCapitalAllocationPct() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal thisAlloc = requestingStrategy.getCapitalAllocationPct() != null
                ? requestingStrategy.getCapitalAllocationPct() : BigDecimal.ZERO;
        BigDecimal totalAlloc = existingAlloc.add(thisAlloc);

        if (totalAlloc.compareTo(account.getMaxCapitalConcentrationPct()) <= 0) return GateVerdict.allow();

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
        return GateVerdict.deny(reason);
    }

    private GateVerdict checkCorrelation(
            AccountStrategy requestingStrategy, Account account, String side, List<UUID> sameSideStrategyIds) {
        if (account.getMaxCorrBlockThreshold() == null) return GateVerdict.allow();

        LocalDate endDate   = LocalDate.now().minusDays(1);
        LocalDate startDate = endDate.minusDays(CORR_WINDOW_DAYS);

        List<StrategyDailyRealizedCurve> thisCurve = curveRepository
                .findByAccountStrategyIdAndCurveDateBetween(
                        requestingStrategy.getAccountStrategyId(), startDate, endDate);
        if (thisCurve.size() < MIN_CORR_DAYS) return GateVerdict.allow();

        Map<LocalDate, Double> thisMap = toCurveMap(thisCurve);
        double threshold = account.getMaxCorrBlockThreshold().doubleValue();
        CheckContext ctx = new CheckContext(requestingStrategy, account, side, threshold, startDate, endDate);

        for (UUID otherId : sameSideStrategyIds) {
            GateVerdict pair = checkCorrelationPair(ctx, otherId, thisMap);
            if (!pair.allowed()) return pair;
        }
        return GateVerdict.allow();
    }

    /**
     * Bundles the requesting-side context that every correlation pair check
     * needs. Reduces {@link #checkCorrelationPair} from 8 to 3 parameters
     * (Sonar S107) without losing any caller information.
     */
    private record CheckContext(
            AccountStrategy requestingStrategy,
            Account account,
            String side,
            double threshold,
            LocalDate startDate,
            LocalDate endDate
    ) {}

    private GateVerdict checkCorrelationPair(
            CheckContext ctx,
            UUID otherId,
            Map<LocalDate, Double> thisMap) {
        List<StrategyDailyRealizedCurve> otherCurve = curveRepository
                .findByAccountStrategyIdAndCurveDateBetween(otherId, ctx.startDate(), ctx.endDate());
        Map<LocalDate, Double> otherMap = toCurveMap(otherCurve);

        List<LocalDate> common = thisMap.keySet().stream()
                .filter(otherMap::containsKey)
                .sorted()
                .toList();
        if (common.size() < MIN_CORR_DAYS) return GateVerdict.allow();

        double[] xs = common.stream().mapToDouble(thisMap::get).toArray();
        double[] ys = common.stream().mapToDouble(otherMap::get).toArray();
        double r = pearson(xs, ys);

        if (r < ctx.threshold()) return GateVerdict.allow();

        String reason = String.format(
                "Correlated strategies blocked: %s vs %s has 30-day P&L correlation r=%.3f >= threshold %.3f " +
                "(side=%s, accountId=%s)",
                ctx.requestingStrategy().getAccountStrategyId(), otherId, r, ctx.threshold(),
                ctx.side().toUpperCase(), ctx.account().getAccountId());
        log.warn("[CorrelationGuard] {}", reason);
        alertService.raise(AlertSeverity.WARN, "CORRELATION_BLOCKED", reason,
                "corr_" + ctx.requestingStrategy().getAccountStrategyId() + "_" + otherId);
        return GateVerdict.deny(reason);
    }

    /**
     * Reduces a daily-realized curve to a date→pnl lookup. The merge function
     * keeps the first occurrence on duplicate curve_date — a defensive guard
     * since the DB unique constraint normally prevents duplicates.
     */
    private static Map<LocalDate, Double> toCurveMap(List<StrategyDailyRealizedCurve> curve) {
        return curve.stream().collect(Collectors.toMap(
                StrategyDailyRealizedCurve::getCurveDate,
                c -> c.getDailyRealizedPnlAmount().doubleValue(),
                (first, dup) -> first));
    }

    /** Pearson product-moment correlation. Returns 0 when variance is zero or n < 3. */
    static double pearson(double[] xs, double[] ys) {
        int n = xs.length;
        if (n < 3) return 0.0;
        double meanX = Arrays.stream(xs).average().orElse(0.0);
        double meanY = Arrays.stream(ys).average().orElse(0.0);
        double cov = 0;
        double varX = 0;
        double varY = 0;
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
