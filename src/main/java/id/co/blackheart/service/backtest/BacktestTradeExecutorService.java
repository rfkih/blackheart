package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.BacktestTradePosition;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestTradeExecutorService {

    private final BacktestPricingService backtestPricingService;

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_PARTIALLY_CLOSED = "PARTIALLY_CLOSED";

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String EXIT_STRUCTURE_TP1_RUNNER = "TP1_RUNNER";
    private static final String EXIT_STRUCTURE_TP1_TP2_RUNNER = "TP1_TP2_RUNNER";
    private static final String EXIT_STRUCTURE_RUNNER_ONLY = "RUNNER_ONLY";

    private static final String TARGET_ALL = "ALL";

    public void execute(
            BacktestRun backtestRun,
            BacktestState state,
            EnrichedStrategyContext context,
            StrategyDecision decision
    ) {
        if (decision == null
                || decision.getDecisionType() == null
                || DecisionType.HOLD.equals(decision.getDecisionType())) {
            return;
        }

        switch (decision.getDecisionType()) {
            case OPEN_LONG -> storePendingEntry(state, context, decision, "LONG");
            case OPEN_SHORT -> storePendingEntry(state, context, decision, "SHORT");
            case UPDATE_POSITION_MANAGEMENT -> updateOpenPositions(state, decision);
            case CLOSE_LONG, CLOSE_SHORT -> {
                if (context == null || context.getMarketData() == null) {
                    return;
                }

                closeAllOpenPositions(
                        backtestRun,
                        state,
                        context.getMarketData().getClosePrice(),
                        decision.getExitReason() != null ? decision.getExitReason() : decision.getDecisionType().name(),
                        context.getMarketData().getEndTime()
                );
            }
            default -> {
            }
        }
    }

    private void storePendingEntry(
            BacktestState state,
            EnrichedStrategyContext context,
            StrategyDecision decision,
            String side
    ) {
        if (state.getActiveTrade() != null || state.getPendingEntry() != null) {
            return;
        }
        state.setPendingEntry(new BacktestState.PendingEntry(decision, side, context.getFeatureStore()));
        log.debug("Backtest pending entry stored | side={} strategy={}", side, decision.getStrategyCode());
    }

    public void fillPendingEntry(
            BacktestRun backtestRun,
            BacktestState state,
            BigDecimal openPrice,
            LocalDateTime entryTime
    ) {
        BacktestState.PendingEntry pending = state.getPendingEntry();
        if (pending == null) {
            return;
        }

        state.setPendingEntry(null);

        if (state.getActiveTrade() != null) {
            log.warn("Pending entry skipped | asset={} reason=Active trade already exists", backtestRun.getAsset());
            return;
        }

        openTrade(backtestRun, state, pending.decision(), pending.side(), pending.featureStore(), openPrice, entryTime);
    }

    private void openTrade(
            BacktestRun backtestRun,
            BacktestState state,
            StrategyDecision decision,
            String tradeType,
            FeatureStore featureStore,
            BigDecimal rawEntryPrice,
            LocalDateTime entryTime
    ) {
        BigDecimal requestedQuoteAmount = resolveRequestedQuoteAmount(decision);

        if (requestedQuoteAmount.compareTo(BigDecimal.ZERO) <= 0 || safe(rawEntryPrice).compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Backtest {} rejected | asset={} reason=Invalid size or entry price",
                    tradeType, backtestRun.getAsset());
            return;
        }

        BigDecimal entryPrice = backtestPricingService.applyEntrySlippage(
                rawEntryPrice, backtestRun.getSlippagePct(), tradeType
        );

        BigDecimal feePct = safe(backtestRun.getFeePct());
        BigDecimal entryFee = requestedQuoteAmount.multiply(feePct).setScale(8, RoundingMode.HALF_UP);
        BigDecimal effectiveQuote = requestedQuoteAmount.subtract(entryFee);
        BigDecimal totalCashRequired = requestedQuoteAmount;

        BigDecimal totalQty = effectiveQuote.divide(entryPrice, 12, RoundingMode.DOWN);
        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Backtest {} rejected | asset={} reason=Calculated quantity is zero",
                    tradeType, backtestRun.getAsset());
            return;
        }

        if (safe(state.getCashBalance()).compareTo(totalCashRequired) < 0) {
            log.warn("Backtest {} rejected | asset={} reason=Insufficient synthetic cash balance",
                    tradeType, backtestRun.getAsset());
            return;
        }

        BacktestTrade trade = BacktestTrade.builder()
                .backtestTradeId(UUID.randomUUID())
                .backtestRunId(backtestRun.getBacktestRunId())
                .accountStrategyId(backtestRun.getAccountStrategyId())
                .strategyName(resolveStrategyName(backtestRun, decision))
                .asset(backtestRun.getAsset())
                .interval(backtestRun.getInterval())
                .side(tradeType)
                .status(STATUS_OPEN)
                .tradeMode(resolveTradeMode(decision))
                .avgEntryPrice(entryPrice)
                .avgExitPrice(null)
                .totalEntryQty(totalQty)
                .totalEntryQuoteQty(requestedQuoteAmount)
                .totalRemainingQty(totalQty)
                .realizedPnlAmount(BigDecimal.ZERO)
                .realizedPnlPercent(BigDecimal.ZERO)
                .totalFeeAmount(entryFee)
                .entryTrendRegime(featureStore != null ? featureStore.getTrendRegime() : null)
                .entryAdx(featureStore != null ? featureStore.getAdx() : null)
                .entryAtr(featureStore != null ? featureStore.getAtr() : null)
                .entryRsi(featureStore != null ? featureStore.getRsi() : null)
                .exitReason(null)
                .entryTime(entryTime)
                .exitTime(null)
                .build();

        List<PlannedPosition> plan = buildPositionPlan(totalQty, requestedQuoteAmount, entryFee, decision);
        List<BacktestTradePosition> positions = new ArrayList<>();

        for (PlannedPosition planned : plan) {
            positions.add(
                    BacktestTradePosition.builder()
                            .tradePositionId(UUID.randomUUID())
                            .backtestTradeId(trade.getBacktestTradeId())
                            .backtestRunId(backtestRun.getBacktestRunId())
                            .accountStrategyId(backtestRun.getAccountStrategyId())
                            .asset(backtestRun.getAsset())
                            .interval(backtestRun.getInterval())
                            .exchange("BINANCE")
                            .side(tradeType)
                            .positionRole(planned.positionRole())
                            .status(STATUS_OPEN)
                            .entryPrice(entryPrice)
                            .entryQty(planned.entryQty())
                            .entryQuoteQty(planned.entryQuoteQty())
                            .remainingQty(planned.entryQty())
                            .exitPrice(null)
                            .exitExecutedQty(null)
                            .exitExecutedQuoteQty(null)
                            .entryFee(planned.entryFee())
                            .entryFeeCurrency("USDT")
                            .exitFee(null)
                            .exitFeeCurrency(null)
                            .initialStopLossPrice(decision.getStopLossPrice())
                            .currentStopLossPrice(decision.getStopLossPrice())
                            .trailingStopPrice(decision.getTrailingStopPrice())
                            .takeProfitPrice(planned.takeProfitPrice())
                            .highestPriceSinceEntry("LONG".equalsIgnoreCase(tradeType) ? entryPrice : null)
                            .lowestPriceSinceEntry("SHORT".equalsIgnoreCase(tradeType) ? entryPrice : null)
                            .realizedPnlAmount(BigDecimal.ZERO)
                            .realizedPnlPercent(BigDecimal.ZERO)
                            .exitReason(null)
                            .entryTime(entryTime)
                            .exitTime(null)
                            .build()
            );
        }

        state.setCashBalance(safe(state.getCashBalance()).subtract(totalCashRequired));
        state.setActiveTrade(trade);
        state.setActiveTradePositions(new ArrayList<>(positions));

        log.info("Backtest {} opened | timeOpen={} qty={} quote={} fee={} positions={}",
                tradeType,
                trade.getEntryTime(),
                totalQty,
                requestedQuoteAmount,
                entryFee,
                positions.size());
    }

    public void closeSinglePositionFromListener(
            BacktestRun backtestRun,
            BacktestState state,
            BacktestTradePosition position,
            BigDecimal exitPrice,
            String exitReason,
            LocalDateTime exitTime
    ) {
        if (position == null || !STATUS_OPEN.equalsIgnoreCase(position.getStatus())) {
            return;
        }

        BigDecimal remainingQty = safe(position.getRemainingQty());
        BigDecimal entryPrice = safe(position.getEntryPrice());
        BigDecimal cleanExitPrice = safe(exitPrice);

        if (remainingQty.compareTo(BigDecimal.ZERO) <= 0 || cleanExitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        cleanExitPrice = backtestPricingService.applyExitSlippage(
                cleanExitPrice, backtestRun.getSlippagePct(), position.getSide()
        );

        BigDecimal exitQuoteQty = remainingQty.multiply(cleanExitPrice).setScale(8, RoundingMode.HALF_UP);
        BigDecimal exitFee = exitQuoteQty.multiply(safe(backtestRun.getFeePct())).setScale(8, RoundingMode.HALF_UP);

        BigDecimal pnlAmount = calculatePLAmount(
                entryPrice,
                cleanExitPrice,
                remainingQty,
                position.getSide()
        ).subtract(safe(position.getEntryFee())).subtract(exitFee);

        BigDecimal pnlPercent = calculatePLPercent(
                entryPrice,
                cleanExitPrice,
                position.getSide()
        );

        position.setExitPrice(cleanExitPrice);
        position.setExitExecutedQty(remainingQty);
        position.setExitExecutedQuoteQty(exitQuoteQty);
        position.setExitFee(exitFee);
        position.setExitReason(exitReason);
        position.setExitTime(exitTime);
        position.setStatus(STATUS_CLOSED);
        position.setRemainingQty(BigDecimal.ZERO);
        position.setRealizedPnlAmount(pnlAmount);
        position.setRealizedPnlPercent(pnlPercent);

        BigDecimal releasedCash = safe(position.getEntryQuoteQty()).add(pnlAmount);
        state.setCashBalance(safe(state.getCashBalance()).add(releasedCash));

        refreshParentTradeState(state, exitTime);
    }

    private void closeAllOpenPositions(
            BacktestRun backtestRun,
            BacktestState state,
            BigDecimal exitPrice,
            String exitReason,
            LocalDateTime exitTime
    ) {
        if (state.getActiveTradePositions() == null || state.getActiveTradePositions().isEmpty()) {
            return;
        }

        for (BacktestTradePosition position : state.getActiveTradePositions()) {
            if (!STATUS_OPEN.equalsIgnoreCase(position.getStatus())) {
                continue;
            }

            closeSinglePositionFromListener(
                    backtestRun,
                    state,
                    position,
                    exitPrice,
                    exitReason,
                    exitTime
            );
        }
    }

    private void updateOpenPositions(BacktestState state, StrategyDecision decision) {
        if (state.getActiveTradePositions() == null || state.getActiveTradePositions().isEmpty()) {
            return;
        }

        String targetRole = decision.getTargetPositionRole() == null || decision.getTargetPositionRole().isBlank()
                ? TARGET_ALL
                : decision.getTargetPositionRole().trim().toUpperCase();

        for (BacktestTradePosition position : state.getActiveTradePositions()) {
            if (!STATUS_OPEN.equalsIgnoreCase(position.getStatus())) {
                continue;
            }

            if (!TARGET_ALL.equals(targetRole)
                    && (position.getPositionRole() == null
                    || !targetRole.equalsIgnoreCase(position.getPositionRole()))) {
                continue;
            }

            if (decision.getStopLossPrice() != null) {
                position.setCurrentStopLossPrice(decision.getStopLossPrice());
            }

            if (decision.getTrailingStopPrice() != null) {
                position.setTrailingStopPrice(decision.getTrailingStopPrice());
            }

            String role = position.getPositionRole() == null ? "" : position.getPositionRole().trim().toUpperCase();

            if ("SINGLE".equals(role) || "TP1".equals(role)) {
                if (decision.getTakeProfitPrice1() != null) {
                    position.setTakeProfitPrice(decision.getTakeProfitPrice1());
                }
            } else if ("TP2".equals(role)) {
                if (decision.getTakeProfitPrice2() != null) {
                    position.setTakeProfitPrice(decision.getTakeProfitPrice2());
                }
            } else if ("RUNNER".equals(role)) {
                if (decision.getTakeProfitPrice1() == null
                        && decision.getTakeProfitPrice2() == null
                        && decision.getTakeProfitPrice3() == null) {
                    position.setTakeProfitPrice(null);
                }
            }
        }
    }

    private void refreshParentTradeState(BacktestState state, LocalDateTime exitTime) {
        BacktestTrade trade = state.getActiveTrade();
        if (trade == null || state.getActiveTradePositions() == null) {
            return;
        }

        List<BacktestTradePosition> allPositions = state.getActiveTradePositions();

        BigDecimal totalRemainingQty = allPositions.stream()
                .map(BacktestTradePosition::getRemainingQty)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal realizedPnlAmount = allPositions.stream()
                .map(BacktestTradePosition::getRealizedPnlAmount)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalFeeAmount = allPositions.stream()
                .map(p -> safe(p.getEntryFee()).add(safe(p.getExitFee())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalClosedQty = allPositions.stream()
                .map(BacktestTradePosition::getExitExecutedQty)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalClosedQuote = allPositions.stream()
                .map(BacktestTradePosition::getExitExecutedQuoteQty)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgExitPrice = totalClosedQty.compareTo(BigDecimal.ZERO) > 0
                ? totalClosedQuote.divide(totalClosedQty, 8, RoundingMode.HALF_UP)
                : null;

        long openCount = allPositions.stream()
                .filter(p -> STATUS_OPEN.equalsIgnoreCase(p.getStatus()))
                .count();

        trade.setTotalRemainingQty(totalRemainingQty);
        trade.setRealizedPnlAmount(realizedPnlAmount);
        trade.setTotalFeeAmount(totalFeeAmount);
        trade.setAvgExitPrice(avgExitPrice);

        if (safe(trade.getTotalEntryQuoteQty()).compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pnlPercent = realizedPnlAmount
                    .divide(trade.getTotalEntryQuoteQty(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            trade.setRealizedPnlPercent(pnlPercent);
        }

        if (openCount == 0) {
            trade.setStatus(STATUS_CLOSED);
            trade.setExitTime(exitTime);

            state.getCompletedTrades().add(trade);
            state.getCompletedTradePositions().addAll(new ArrayList<>(allPositions));

            state.setActiveTrade(null);
            state.setActiveTradePositions(new ArrayList<>());
        } else if (openCount < allPositions.size()) {
            trade.setStatus(STATUS_PARTIALLY_CLOSED);
        } else {
            trade.setStatus(STATUS_OPEN);
        }
    }

    private BigDecimal resolveRequestedQuoteAmount(StrategyDecision decision) {
        if (decision == null) {
            return BigDecimal.ZERO;
        }

        if (decision.getNotionalSize() != null && decision.getNotionalSize().compareTo(BigDecimal.ZERO) > 0) {
            return decision.getNotionalSize();
        }

        if (decision.getPositionSize() != null && decision.getPositionSize().compareTo(BigDecimal.ZERO) > 0) {
            return decision.getPositionSize();
        }

        return BigDecimal.ZERO;
    }

    private String resolveStrategyName(BacktestRun backtestRun, StrategyDecision decision) {
        if (decision != null && decision.getStrategyCode() != null && !decision.getStrategyCode().isBlank()) {
            return decision.getStrategyCode();
        }

        if (backtestRun.getStrategyCode() != null && !backtestRun.getStrategyCode().isBlank()) {
            return backtestRun.getStrategyCode();
        }

        return backtestRun.getStrategyName();
    }

    private List<PlannedPosition> buildPositionPlan(
            BigDecimal totalQty,
            BigDecimal totalQuoteAmount,
            BigDecimal totalEntryFee,
            StrategyDecision decision
    ) {
        String tradeMode = resolveTradeMode(decision);

        return switch (tradeMode) {
            case EXIT_STRUCTURE_TP1_RUNNER -> buildTwoPositionPlan(
                    totalQty, totalQuoteAmount, totalEntryFee, decision
            );
            case EXIT_STRUCTURE_TP1_TP2_RUNNER -> buildThreePositionPlan(
                    totalQty, totalQuoteAmount, totalEntryFee, decision
            );
            case EXIT_STRUCTURE_RUNNER_ONLY -> List.of(
                    new PlannedPosition("RUNNER", totalQty, totalQuoteAmount, totalEntryFee, null)
            );
            default -> List.of(
                    new PlannedPosition("SINGLE", totalQty, totalQuoteAmount, totalEntryFee, decision.getTakeProfitPrice1())
            );
        };
    }

    private List<PlannedPosition> buildTwoPositionPlan(
            BigDecimal totalQty,
            BigDecimal totalQuoteAmount,
            BigDecimal totalEntryFee,
            StrategyDecision decision
    ) {
        BigDecimal tp1Qty = totalQty.multiply(new BigDecimal("0.50")).setScale(12, RoundingMode.DOWN);
        BigDecimal runnerQty = totalQty.subtract(tp1Qty);

        BigDecimal tp1Quote = totalQuoteAmount.multiply(new BigDecimal("0.50")).setScale(8, RoundingMode.DOWN);
        BigDecimal runnerQuote = totalQuoteAmount.subtract(tp1Quote);

        BigDecimal tp1Fee = totalEntryFee.multiply(new BigDecimal("0.50")).setScale(8, RoundingMode.DOWN);
        BigDecimal runnerFee = totalEntryFee.subtract(tp1Fee);

        return List.of(
                new PlannedPosition("TP1", tp1Qty, tp1Quote, tp1Fee, decision.getTakeProfitPrice1()),
                new PlannedPosition("RUNNER", runnerQty, runnerQuote, runnerFee, null)
        );
    }

    private List<PlannedPosition> buildThreePositionPlan(
            BigDecimal totalQty,
            BigDecimal totalQuoteAmount,
            BigDecimal totalEntryFee,
            StrategyDecision decision
    ) {
        BigDecimal tp1Qty = totalQty.multiply(new BigDecimal("0.30")).setScale(12, RoundingMode.DOWN);
        BigDecimal tp2Qty = totalQty.multiply(new BigDecimal("0.30")).setScale(12, RoundingMode.DOWN);
        BigDecimal runnerQty = totalQty.subtract(tp1Qty).subtract(tp2Qty);

        BigDecimal tp1Quote = totalQuoteAmount.multiply(new BigDecimal("0.30")).setScale(8, RoundingMode.DOWN);
        BigDecimal tp2Quote = totalQuoteAmount.multiply(new BigDecimal("0.30")).setScale(8, RoundingMode.DOWN);
        BigDecimal runnerQuote = totalQuoteAmount.subtract(tp1Quote).subtract(tp2Quote);

        BigDecimal tp1Fee = totalEntryFee.multiply(new BigDecimal("0.30")).setScale(8, RoundingMode.DOWN);
        BigDecimal tp2Fee = totalEntryFee.multiply(new BigDecimal("0.30")).setScale(8, RoundingMode.DOWN);
        BigDecimal runnerFee = totalEntryFee.subtract(tp1Fee).subtract(tp2Fee);

        return List.of(
                new PlannedPosition("TP1", tp1Qty, tp1Quote, tp1Fee, decision.getTakeProfitPrice1()),
                new PlannedPosition("TP2", tp2Qty, tp2Quote, tp2Fee, decision.getTakeProfitPrice2()),
                new PlannedPosition("RUNNER", runnerQty, runnerQuote, runnerFee, null)
        );
    }

    private String resolveTradeMode(StrategyDecision decision) {
        if (decision == null || decision.getExitStructure() == null || decision.getExitStructure().isBlank()) {
            return EXIT_STRUCTURE_SINGLE;
        }
        return decision.getExitStructure().trim().toUpperCase();
    }

    private BigDecimal calculatePLAmount(
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal qty,
            String side
    ) {
        if ("SHORT".equalsIgnoreCase(side)) {
            return entryPrice.subtract(exitPrice).multiply(qty);
        }
        return exitPrice.subtract(entryPrice).multiply(qty);
    }

    private BigDecimal calculatePLPercent(
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            String side
    ) {
        if (entryPrice == null || exitPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal move = "SHORT".equalsIgnoreCase(side)
                ? entryPrice.subtract(exitPrice)
                : exitPrice.subtract(entryPrice);

        return move.divide(entryPrice, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record PlannedPosition(
            String positionRole,
            BigDecimal entryQty,
            BigDecimal entryQuoteQty,
            BigDecimal entryFee,
            BigDecimal takeProfitPrice
    ) {
    }
}