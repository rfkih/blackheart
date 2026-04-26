package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.response.*;
import id.co.blackheart.model.*;
import id.co.blackheart.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestQueryService {

    private final BacktestRunRepository backtestRunRepository;
    private final BacktestEquityPointRepository equityPointRepository;
    private final BacktestTradeRepository backtestTradeRepository;
    private final BacktestTradePositionRepository backtestTradePositionRepository;
    private final MarketDataRepository marketDataRepository;
    private final ObjectMapper objectMapper;

    /**
     * Whitelist of columns the client may sort by. Any value outside this
     * set is silently coerced to {@code createdAt} — the native query's
     * ORDER BY uses CASE-matching and falls back to created_time when no
     * case matches, so an unknown sort key produces a safe (if boring)
     * default rather than a server error.
     */
    private static final Set<String> SORTABLE_COLUMNS = Set.of(
            "createdAt", "returnPct", "sharpe", "maxDrawdownPct",
            "totalTrades", "winRate", "status", "symbol", "strategyCode"
    );

    public Map<String, Object> listRuns(
            UUID userId,
            int page,
            int size,
            String status,
            String strategyCode,
            String symbol,
            String intervalName,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            String sortBy,
            String sortDir
    ) {
        int effectiveSize = size > 0 ? size : 20;
        int offset = Math.max(0, page) * effectiveSize;

        String effectiveSort = (sortBy != null && SORTABLE_COLUMNS.contains(sortBy))
                ? sortBy
                : "createdAt";
        String effectiveDir = "ASC".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";

        // Normalise blank strings to null so the CAST(:param AS TEXT) IS NULL
        // guard in the repository query activates correctly. Spring's binder
        // delivers empty strings when the client sends `?status=` — those
        // should disable the filter, not match rows where status = ''.
        String statusFilter = blankToNull(status);
        String strategyFilter = blankToNull(strategyCode);
        String symbolFilter = blankToNull(symbol);
        String intervalFilter = blankToNull(intervalName);

        List<BacktestRun> runs = backtestRunRepository.findFiltered(
                userId,
                statusFilter,
                strategyFilter,
                symbolFilter,
                intervalFilter,
                fromDate,
                toDate,
                effectiveSort,
                effectiveDir,
                effectiveSize,
                offset
        );
        long total = backtestRunRepository.countFiltered(
                userId,
                statusFilter,
                strategyFilter,
                symbolFilter,
                intervalFilter,
                fromDate,
                toDate
        );

        // Include metrics on the list — the frontend run table renders Return,
        // Sharpe, and Max DD columns, and an all-null column reads as a data
        // failure rather than the intended "still RUNNING" state.
        List<BacktestRunDetailResponse> content = runs.stream()
                .map(r -> toDetail(r, true))
                .collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("page", page);
        result.put("size", effectiveSize);
        result.put("total", total);
        result.put("sortBy", effectiveSort);
        result.put("sortDir", effectiveDir);
        return result;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * Fetch a run detail scoped to the caller. Collapses "not found" and
     * "not yours" into the same 404 to avoid leaking existence.
     */
    public BacktestRunDetailResponse getRun(UUID userId, UUID id) {
        BacktestRun run = requireOwnedRun(userId, id);
        return toDetail(run, true);
    }

    /**
     * Backwards-compat overload for callers that already validated ownership
     * (e.g. the submit path that just created the run). Do NOT call from
     * untrusted request paths.
     */
    public BacktestRunDetailResponse getRun(UUID id) {
        BacktestRun run = backtestRunRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Not found"));
        return toDetail(run, true);
    }

    public List<BacktestEquityPointResponse> getEquityPoints(UUID userId, UUID id) {
        requireOwnedRun(userId, id);
        return equityPointRepository.findByBacktestRunIdOrderByEquityDateAsc(id).stream()
                .map(this::toEquityPoint)
                .collect(Collectors.toList());
    }

    public List<BacktestTradeDetailResponse> getTrades(UUID userId, UUID id) {
        requireOwnedRun(userId, id);
        List<BacktestTrade> trades = backtestTradeRepository.findAllByBacktestRunId(id);
        return trades.stream()
                .map(t -> toTradeDetail(t))
                .collect(Collectors.toList());
    }

    public List<MarketDataResponse> getCandles(UUID userId, UUID id) {
        BacktestRun run = requireOwnedRun(userId, id);
        List<MarketData> candles = marketDataRepository.findBySymbolIntervalAndRange(
                run.getAsset(), run.getInterval(), run.getStartTime(), run.getEndTime());
        return candles.stream().map(this::toMarketData).collect(Collectors.toList());
    }

    private BacktestRun requireOwnedRun(UUID userId, UUID id) {
        if (userId == null || id == null) {
            throw new EntityNotFoundException("Not found");
        }
        return backtestRunRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new EntityNotFoundException("Not found"));
    }

    private BacktestRunDetailResponse toDetail(BacktestRun r, boolean withMetrics) {
        BacktestMetricsResponse metrics = null;
        if (withMetrics && "COMPLETED".equalsIgnoreCase(r.getStatus())) {
            metrics = BacktestMetricsResponse.builder()
                    .totalReturn(r.getNetProfit())
                    .totalReturnPct(r.getReturnPct())
                    .winRate(r.getWinRate())
                    .profitFactor(r.getProfitFactor())
                    .avgWinUsdt(r.getAvgWin())
                    .avgLossUsdt(r.getAvgLoss())
                    .maxDrawdown(r.getMaxDrawdownAmount())
                    .maxDrawdownPct(r.getMaxDrawdownPct())
                    .sharpe(r.getSharpeRatio())
                    .sortino(r.getSortinoRatio())
                    .psr(r.getPsr())
                    .totalTrades(r.getTotalTrades())
                    .winningTrades(r.getTotalWins())
                    .losingTrades(r.getTotalLosses())
                    .build();
        }

        Object paramSnapshot = null;
        if (r.getConfigSnapshot() != null) {
            try {
                paramSnapshot = objectMapper.readValue(r.getConfigSnapshot(), Object.class);
            } catch (Exception e) {
                paramSnapshot = r.getConfigSnapshot();
            }
        }

        String errorMsg = "FAILED".equalsIgnoreCase(r.getStatus()) ? r.getNotes() : null;

        return BacktestRunDetailResponse.builder()
                .id(r.getBacktestRunId())
                .accountStrategyId(r.getAccountStrategyId())
                .strategyAccountStrategyIds(r.getStrategyAccountStrategyIds())
                .strategyCode(r.getStrategyCode())
                .strategyName(r.getStrategyName())
                .symbol(r.getAsset())
                .interval(r.getInterval())
                .status(r.getStatus())
                .progressPercent(r.getProgressPercent())
                .fromDate(r.getStartTime())
                .toDate(r.getEndTime())
                .initialCapital(r.getInitialCapital())
                .endingBalance(r.getEndingBalance())
                .errorMessage(errorMsg)
                .createdAt(r.getCreatedTime())
                .completedAt("COMPLETED".equalsIgnoreCase(r.getStatus()) ? r.getUpdatedTime() : null)
                .paramSnapshot(paramSnapshot)
                .gitCommitSha(r.getGitCommitSha())
                .appVersion(r.getAppVersion())
                .metrics(metrics)
                .build();
    }

    private BacktestEquityPointResponse toEquityPoint(BacktestEquityPoint ep) {
        long ts = ep.getEquityDate().atTime(LocalTime.MIDNIGHT).toInstant(ZoneOffset.UTC).toEpochMilli();
        BigDecimal drawdown = ep.getTotalEquity() != null && ep.getDrawdownPercent() != null
                ? ep.getTotalEquity().multiply(ep.getDrawdownPercent()).divide(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        return BacktestEquityPointResponse.builder()
                .ts(ts)
                .equity(ep.getTotalEquity())
                .drawdown(drawdown)
                .drawdownPct(ep.getDrawdownPercent())
                .build();
    }

    private BacktestTradeDetailResponse toTradeDetail(BacktestTrade t) {
        List<BacktestTradePosition> positions = backtestTradePositionRepository.findAllByTradeId(t.getBacktestTradeId());

        BigDecimal tp1 = positionTp(positions, "TP1");
        BigDecimal tp2 = positionTp(positions, "TP2");
        if (tp1 == null) tp1 = positionTp(positions, "SINGLE");

        List<BacktestPositionDetailResponse> positionResponses = positions.stream()
                .map(p -> BacktestPositionDetailResponse.builder()
                        .id(p.getTradePositionId())
                        .type(p.getPositionRole())
                        .quantity(p.getEntryQty())
                        .exitTime(toEpochMs(p.getExitTime()))
                        .exitPrice(p.getExitPrice())
                        .exitReason(p.getExitReason())
                        .realizedPnl(p.getRealizedPnlAmount())
                        .build())
                .collect(Collectors.toList());

        return BacktestTradeDetailResponse.builder()
                .id(t.getBacktestTradeId())
                .backtestRunId(t.getBacktestRunId())
                .strategyCode(t.getStrategyCode())
                .strategyName(t.getStrategyName())
                .interval(t.getInterval())
                .direction(t.getSide())
                .entryTime(toEpochMs(t.getEntryTime()))
                .entryPrice(t.getAvgEntryPrice())
                .exitTime(toEpochMs(t.getExitTime()))
                .exitPrice(t.getAvgExitPrice())
                .stopLossPrice(t.getInitialStopLossPrice())
                .tp1Price(tp1)
                .tp2Price(tp2)
                .quantity(t.getTotalEntryQty())
                .realizedPnl(t.getRealizedPnlAmount())
                .rMultiple(t.getRealizedRMultiple())
                .positions(positionResponses)
                .build();
    }

    private BigDecimal positionTp(List<BacktestTradePosition> positions, String role) {
        return positions.stream()
                .filter(p -> role.equalsIgnoreCase(p.getPositionRole()))
                .map(BacktestTradePosition::getTakeProfitPrice)
                .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                .findFirst().orElse(null);
    }

    private MarketDataResponse toMarketData(MarketData m) {
        return MarketDataResponse.builder()
                .symbol(m.getSymbol())
                .interval(m.getInterval())
                .openTime(toEpochMs(m.getStartTime()))
                .open(m.getOpenPrice())
                .high(m.getHighPrice())
                .low(m.getLowPrice())
                .close(m.getClosePrice())
                .volume(m.getVolume())
                .closeTime(toEpochMs(m.getEndTime()))
                .build();
    }

    private Long toEpochMs(LocalDateTime ldt) {
        if (ldt == null) return null;
        return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
