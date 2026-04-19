package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.response.*;
import id.co.blackheart.model.*;
import id.co.blackheart.repository.*;
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

    public Map<String, Object> listRuns(int page, int size) {
        int effectiveSize = size > 0 ? size : 20;
        int offset = page * effectiveSize;
        List<BacktestRun> runs = backtestRunRepository.findAllOrderByCreatedTimeDesc(effectiveSize, offset);
        long total = backtestRunRepository.countAll();
        List<BacktestRunDetailResponse> content = runs.stream()
                .map(r -> toDetail(r, false))
                .collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("page", page);
        result.put("size", effectiveSize);
        result.put("total", total);
        return result;
    }

    public BacktestRunDetailResponse getRun(UUID id) {
        BacktestRun run = backtestRunRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Backtest run not found: " + id));
        return toDetail(run, true);
    }

    public List<BacktestEquityPointResponse> getEquityPoints(UUID id) {
        return equityPointRepository.findByBacktestRunIdOrderByEquityDateAsc(id).stream()
                .map(this::toEquityPoint)
                .collect(Collectors.toList());
    }

    public List<BacktestTradeDetailResponse> getTrades(UUID id) {
        List<BacktestTrade> trades = backtestTradeRepository.findAllByBacktestRunId(id);
        return trades.stream()
                .map(t -> toTradeDetail(t))
                .collect(Collectors.toList());
    }

    public List<MarketDataResponse> getCandles(UUID id) {
        BacktestRun run = backtestRunRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Backtest run not found: " + id));
        List<MarketData> candles = marketDataRepository.findBySymbolIntervalAndRange(
                run.getAsset(), run.getInterval(), run.getStartTime(), run.getEndTime());
        return candles.stream().map(this::toMarketData).collect(Collectors.toList());
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
                .fromDate(r.getStartTime())
                .toDate(r.getEndTime())
                .initialCapital(r.getInitialCapital())
                .endingBalance(r.getEndingBalance())
                .errorMessage(errorMsg)
                .createdAt(r.getCreatedTime())
                .completedAt("COMPLETED".equalsIgnoreCase(r.getStatus()) ? r.getUpdatedTime() : null)
                .paramSnapshot(paramSnapshot)
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
