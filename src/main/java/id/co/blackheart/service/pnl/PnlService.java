package id.co.blackheart.service.pnl;

import id.co.blackheart.dto.response.DailyPnlResponse;
import id.co.blackheart.dto.response.EquityPointResponse;
import id.co.blackheart.dto.response.PnlSummaryResponse;
import id.co.blackheart.dto.response.StrategyPnlResponse;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.cache.CacheService;
import id.co.blackheart.service.statistics.GeometricReturnCalculator;
import id.co.blackheart.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PnlService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DEFAULT_INITIAL_CAPITAL = BigDecimal.valueOf(10_000);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TradesRepository tradesRepository;
    private final AccountRepository accountRepository;
    private final CacheService cacheService;

    public PnlSummaryResponse getSummary(UUID userId, String period) {
        List<UUID> accountIds = getAccountIds(userId);
        LocalDateTime[] range = periodRange(period);
        LocalDateTime from = range[0];
        LocalDateTime to = range[1];

        List<Trades> closed = tradesRepository.findClosedInPeriodByAccountIds(accountIds, from, to);
        List<Trades> open = tradesRepository.findOpenByAccountIds(accountIds);

        BigDecimal realizedPnl = closed.stream()
                .map(t -> ObjectUtils.isNotEmpty(t.getRealizedPnlAmount()) ? t.getRealizedPnlAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long wins = closed.stream()
                .filter(t -> ObjectUtils.isNotEmpty(t.getRealizedPnlAmount()) && t.getRealizedPnlAmount().compareTo(BigDecimal.ZERO) > 0)
                .count();

        BigDecimal winRate = closed.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(closed.size()), 4, RoundingMode.HALF_UP).multiply(HUNDRED);

        BigDecimal unrealizedPnl = computeUnrealizedPnl(open);
        BigDecimal totalPnl = realizedPnl.add(unrealizedPnl);

        GeometricReturnCalculator.Result tr = computePerTradeReturnStats(closed);

        return PnlSummaryResponse.builder()
                .period(period)
                .realizedPnl(realizedPnl.setScale(8, RoundingMode.HALF_UP))
                .unrealizedPnl(unrealizedPnl.setScale(8, RoundingMode.HALF_UP))
                .totalPnl(totalPnl.setScale(8, RoundingMode.HALF_UP))
                .tradeCount(closed.size())
                .winRate(winRate)
                .openCount(open.size())
                .avgTradeReturnPct(tr.avgTradeReturnPct())
                .geometricReturnPctAtAlloc90(tr.geometricReturnPct())
                .build();
    }

    /**
     * Per-trade return stats over the closed-trade slice. Sorts by entry time
     * so the geometric compound walks the live sequence rather than whatever
     * order the repo returned (defensive — repo currently sorts but we don't
     * want this method to break if that changes). Trades with non-positive
     * notional are skipped inside the calculator.
     */
    private GeometricReturnCalculator.Result computePerTradeReturnStats(List<Trades> closed) {
        if (ObjectUtils.isEmpty(closed)) {
            return GeometricReturnCalculator.Result.zero();
        }
        List<GeometricReturnCalculator.TradeReturn> series = closed.stream()
                .sorted(Comparator.comparing(Trades::getEntryTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(t -> new GeometricReturnCalculator.TradeReturn(
                        t.getRealizedPnlAmount(), t.getTotalEntryQuoteQty()))
                .toList();
        return GeometricReturnCalculator.compute(series);
    }

    public List<DailyPnlResponse> getDaily(UUID userId, LocalDate from, LocalDate to, String strategyCode) {
        List<UUID> accountIds = getAccountIds(userId);
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);

        List<Trades> closed = tradesRepository.findClosedInPeriodByAccountIds(accountIds, fromDt, toDt);

        if (StringUtils.hasText(strategyCode)) {
            closed = closed.stream()
                    .filter(t -> strategyCode.equalsIgnoreCase(t.getStrategyName()))
                    .toList();
        }

        Map<String, List<Trades>> byDate = new TreeMap<>();
        for (Trades t : closed) {
            String dateKey = t.getExitTime().toLocalDate().format(DATE_FMT);
            byDate.computeIfAbsent(dateKey, k -> new ArrayList<>()).add(t);
        }

        return byDate.entrySet().stream().map(e -> {
            BigDecimal pnl = e.getValue().stream()
                    .map(t -> t.getRealizedPnlAmount() != null ? t.getRealizedPnlAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return DailyPnlResponse.builder()
                    .date(e.getKey())
                    .realizedPnl(pnl.setScale(8, RoundingMode.HALF_UP))
                    .tradeCount(e.getValue().size())
                    .build();
        }).toList();
    }

    public List<StrategyPnlResponse> getByStrategy(UUID userId, LocalDate from, LocalDate to) {
        List<UUID> accountIds = getAccountIds(userId);
        LocalDateTime fromDt = (ObjectUtils.isNotEmpty(from)) ? from.atStartOfDay() : LocalDateTime.of(2020, 1, 1, 0, 0);
        LocalDateTime toDt = (ObjectUtils.isNotEmpty(to)) ? to.atTime(LocalTime.MAX) : LocalDateTime.now(ZoneOffset.UTC);

        List<Trades> closed = tradesRepository.findClosedInPeriodByAccountIds(accountIds, fromDt, toDt);

        Map<String, List<Trades>> byStrategy = new TreeMap<>();
        for (Trades t : closed) {
            String code = ObjectUtils.isNotEmpty(t.getStrategyName()) ? t.getStrategyName() : "UNKNOWN";
            byStrategy.computeIfAbsent(code, k -> new ArrayList<>()).add(t);
        }

        return byStrategy.entrySet().stream().map(e -> {
            List<Trades> list = e.getValue();
            BigDecimal pnl = list.stream()
                    .map(t -> t.getRealizedPnlAmount() != null ? t.getRealizedPnlAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long wins = list.stream()
                    .filter(t -> t.getRealizedPnlAmount() != null && t.getRealizedPnlAmount().compareTo(BigDecimal.ZERO) > 0)
                    .count();
            BigDecimal winRate = list.isEmpty() ? BigDecimal.ZERO
                    : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(list.size()), 4, RoundingMode.HALF_UP).multiply(HUNDRED);
            GeometricReturnCalculator.Result tr = computePerTradeReturnStats(list);
            return StrategyPnlResponse.builder()
                    .strategyCode(e.getKey())
                    .realizedPnl(pnl.setScale(8, RoundingMode.HALF_UP))
                    .tradeCount(list.size())
                    .winRate(winRate)
                    .avgTradeReturnPct(tr.avgTradeReturnPct())
                    .geometricReturnPctAtAlloc90(tr.geometricReturnPct())
                    .build();
        }).toList();
    }

    /**
     * Build the equity curve for one account over the requested window.
     *
     * Equity is modeled as: {@code baseline + cumulativeRealizedPnl}.
     * Cumulative pnl is carried across the window boundary so the equity at
     * {@code from} reflects every trade closed before that moment, not just
     * trades inside the window.
     *
     * Drawdown is the percentage drop from the running peak (≤ 0).
     *
     * @throws SecurityException if {@code accountId} doesn't belong to {@code userId}.
     */
    public List<EquityPointResponse> getEquityCurve(
            UUID userId,
            UUID accountId,
            long fromMs,
            long toMs,
            BigDecimal initialCapital
    ) {
        // Authorization: callers can only see their own accounts.
        boolean owns = accountRepository.findByUserId(userId).stream()
                .anyMatch(a -> accountId.equals(a.getAccountId()));
        if (!owns) {
            throw new SecurityException("Account does not belong to caller");
        }

        if (toMs < fromMs) {
            return Collections.emptyList();
        }

        BigDecimal baseline = (ObjectUtils.isNotEmpty(initialCapital) && initialCapital.signum() > 0)
                ? initialCapital
                : DEFAULT_INITIAL_CAPITAL;

        LocalDateTime fromDt = Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime toDt = Instant.ofEpochMilli(toMs).atZone(ZoneOffset.UTC).toLocalDateTime();

        List<Trades> closed = tradesRepository.findClosedByAccountIdUpTo(accountId, toDt);

        // Walk all closed trades up to `to` so cumulative pnl is accurate at the
        // window's start; only emit points whose exit time falls inside the window.
        EquityCurveState state = new EquityCurveState(baseline);
        for (Trades t : closed) {
            applyTrade(state, t, baseline, fromDt, fromMs);
        }

        // No trades closed in-window: emit a flat anchor so the chart still has
        // something to draw at the requested baseline.
        if (!state.fromBoundaryRecorded) {
            BigDecimal equityAtFrom = baseline.add(state.cumulative);
            state.points.add(EquityPointResponse.builder()
                    .time(fromMs)
                    .equity(equityAtFrom.setScale(8, RoundingMode.HALF_UP))
                    .drawdown(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                    .build());
        }

        // Trailing anchor at `to` so the line extends to the right edge of the window.
        EquityPointResponse last = state.points.get(state.points.size() - 1);
        if (!Objects.equals(last.getTime(), toMs)) {
            state.points.add(EquityPointResponse.builder()
                    .time(toMs)
                    .equity(last.getEquity())
                    .drawdown(last.getDrawdown())
                    .build());
        }

        return state.points;
    }

    private void applyTrade(EquityCurveState state, Trades t, BigDecimal baseline,
                            LocalDateTime fromDt, long fromMs) {
        LocalDateTime exit = t.getExitTime();
        if (ObjectUtils.isEmpty(exit)) return;
        BigDecimal pnl = ObjectUtils.isNotEmpty(t.getRealizedPnlAmount()) ? t.getRealizedPnlAmount() : BigDecimal.ZERO;

        // Carry pnl that closed BEFORE the window into the starting baseline.
        if (exit.isBefore(fromDt)) {
            state.cumulative = state.cumulative.add(pnl);
            return;
        }

        recordWindowEdge(state, baseline, fromMs);

        state.cumulative = state.cumulative.add(pnl);
        BigDecimal equity = baseline.add(state.cumulative);
        if (equity.compareTo(state.peak) > 0) state.peak = equity;

        state.points.add(EquityPointResponse.builder()
                .time(DateTimeUtil.toEpochMillisUtc(exit))
                .equity(equity.setScale(8, RoundingMode.HALF_UP))
                .drawdown(percentDrop(equity, state.peak))
                .build());
    }

    /**
     * First trade in-window: anchor a point at {@code from} representing the
     * equity right at the window's left edge. No-op on subsequent calls.
     */
    private void recordWindowEdge(EquityCurveState state, BigDecimal baseline, long fromMs) {
        if (state.fromBoundaryRecorded) return;
        BigDecimal equityAtFrom = baseline.add(state.cumulative);
        if (equityAtFrom.compareTo(state.peak) > 0) state.peak = equityAtFrom;
        state.points.add(EquityPointResponse.builder()
                .time(fromMs)
                .equity(equityAtFrom.setScale(8, RoundingMode.HALF_UP))
                .drawdown(percentDrop(equityAtFrom, state.peak))
                .build());
        state.fromBoundaryRecorded = true;
    }

    private static final class EquityCurveState {
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak;
        boolean fromBoundaryRecorded = false;
        final List<EquityPointResponse> points = new ArrayList<>();

        EquityCurveState(BigDecimal baseline) {
            this.peak = baseline;
        }
    }

    private BigDecimal percentDrop(BigDecimal equity, BigDecimal peak) {
        if (peak.signum() <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return equity.subtract(peak)
                .divide(peak, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal computeUnrealizedPnl(List<Trades> openTrades) {
        BigDecimal total = BigDecimal.ZERO;
        for (Trades t : openTrades) {
            try {
                BigDecimal price = cacheService.getLatestPrice(t.getAsset());
                if (ObjectUtils.isEmpty(price) || ObjectUtils.isEmpty(t.getAvgEntryPrice()) || ObjectUtils.isEmpty(t.getTotalRemainingQty())) continue;
                BigDecimal pnl;
                if ("SHORT".equalsIgnoreCase(t.getSide())) {
                    pnl = t.getAvgEntryPrice().subtract(price).multiply(t.getTotalRemainingQty());
                } else {
                    pnl = price.subtract(t.getAvgEntryPrice()).multiply(t.getTotalRemainingQty());
                }
                total = total.add(pnl);
            } catch (Exception e) {
                log.warn("Could not compute unrealized pnl for trade {}", t.getTradeId());
            }
        }
        return total;
    }

    private List<UUID> getAccountIds(UUID userId) {
        return accountRepository.findByUserId(userId).stream()
                .map(Account::getAccountId)
                .toList();
    }

    private LocalDateTime[] periodRange(String period) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime from;
        switch (period.toLowerCase()) {
            case "week":
                from = now.toLocalDate().atStartOfDay().minusDays((long) now.getDayOfWeek().getValue() - 1);
                break;
            case "month":
                from = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
                break;
            default:
                from = now.toLocalDate().atStartOfDay();
        }
        return new LocalDateTime[]{from, now};
    }
}
