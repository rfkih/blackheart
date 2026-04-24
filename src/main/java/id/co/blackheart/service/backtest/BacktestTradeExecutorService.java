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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
        state.setPendingEntry(new BacktestState.PendingEntry(decision, side, context.getFeatureStore(), context.getBiasFeatureStore()));
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

        // Cancel fill if the open price has gapped through the stop loss
        BigDecimal slPrice = pending.decision().getStopLossPrice();
        if (slPrice != null && slPrice.compareTo(BigDecimal.ZERO) > 0) {
            boolean gappedThroughSl = "LONG".equalsIgnoreCase(pending.side())
                    ? openPrice.compareTo(slPrice) <= 0
                    : openPrice.compareTo(slPrice) >= 0;
            if (gappedThroughSl) {
                log.warn("Pending entry cancelled | asset={} side={} reason=Gap through SL openPrice={} slPrice={}",
                        backtestRun.getAsset(), pending.side(), openPrice, slPrice);
                return;
            }
        }

        openTrade(backtestRun, state, pending.decision(), pending.side(), pending.featureStore(), pending.biasFeatureStore(), openPrice, entryTime);
    }

    private void openTrade(
            BacktestRun backtestRun,
            BacktestState state,
            StrategyDecision decision,
            String tradeType,
            FeatureStore featureStore,
            FeatureStore biasFeatureStore,
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

        // Slippage delta: positive = worse fill than raw price (always adverse).
        // LONG: entryPrice > rawEntryPrice → slippage = entryPrice - rawEntryPrice
        // SHORT: entryPrice < rawEntryPrice → slippage = rawEntryPrice - entryPrice
        BigDecimal slippagePerUnit = "LONG".equalsIgnoreCase(tradeType)
                ? entryPrice.subtract(rawEntryPrice)
                : rawEntryPrice.subtract(entryPrice);
        slippagePerUnit = slippagePerUnit.max(BigDecimal.ZERO);

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

        // ── Initial risk computation ──────────────────────────────────────────
        BigDecimal slPrice = decision.getStopLossPrice();
        BigDecimal initialRiskPerUnit = BigDecimal.ZERO;
        if (slPrice != null && slPrice.compareTo(BigDecimal.ZERO) > 0) {
            initialRiskPerUnit = "LONG".equalsIgnoreCase(tradeType)
                    ? entryPrice.subtract(slPrice)
                    : slPrice.subtract(entryPrice);
            if (initialRiskPerUnit.compareTo(BigDecimal.ZERO) < 0) {
                initialRiskPerUnit = BigDecimal.ZERO;
            }
        }
        BigDecimal initialRiskAmount = initialRiskPerUnit.multiply(totalQty).setScale(8, RoundingMode.HALF_UP);
        BigDecimal initialRiskPercent = requestedQuoteAmount.compareTo(BigDecimal.ZERO) > 0
                ? initialRiskAmount.divide(requestedQuoteAmount, 8, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        BacktestTrade trade = BacktestTrade.builder()
                .backtestTradeId(UUID.randomUUID())
                .backtestRunId(backtestRun.getBacktestRunId())
                .accountStrategyId(backtestRun.getAccountStrategyId())
                .strategyCode(resolveStrategyName(backtestRun, decision))
                .strategyName(resolveStrategyName(backtestRun, decision))
                .strategyVersion(decision.getStrategyVersion())
                .asset(backtestRun.getAsset())
                .interval(backtestRun.getInterval())
                .exchange("BINANCE")
                .side(tradeType)
                .status(STATUS_OPEN)
                .tradeMode(resolveTradeMode(decision))
                .signalType(decision.getSignalType())
                .setupType(decision.getSetupType())
                .entryReason(decision.getReason())
                .notionalSize(requestedQuoteAmount)
                .avgEntryPrice(entryPrice)
                .avgExitPrice(null)
                .totalEntryQty(totalQty)
                .totalEntryQuoteQty(requestedQuoteAmount)
                .totalRemainingQty(totalQty)
                .initialStopLossPrice(slPrice)
                .initialTrailingStopPrice(decision.getTrailingStopPrice())
                .initialRiskPerUnit(initialRiskPerUnit)
                .initialRiskAmount(initialRiskAmount)
                .initialRiskPercent(initialRiskPercent)
                .grossPnlAmount(BigDecimal.ZERO)
                .realizedPnlAmount(BigDecimal.ZERO)
                .realizedPnlPercent(BigDecimal.ZERO)
                .totalFeeAmount(entryFee)
                .totalFeeCurrency("USDT")
                .slippageAmount(slippagePerUnit.multiply(totalQty).setScale(8, RoundingMode.HALF_UP))
                .slippagePercent(requestedQuoteAmount.compareTo(BigDecimal.ZERO) > 0
                        ? slippagePerUnit.multiply(totalQty)
                                .divide(requestedQuoteAmount, 8, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                        : BigDecimal.ZERO)
                .highestPriceDuringTrade(entryPrice)
                .lowestPriceDuringTrade(entryPrice)
                .entrySignalScore(decision.getSignalScore())
                .entryConfidenceScore(decision.getConfidenceScore())
                .entryTrendRegime(featureStore != null ? featureStore.getTrendRegime() : null)
                .entryAdx(featureStore != null ? featureStore.getAdx() : null)
                .entryAtr(featureStore != null ? featureStore.getAtr() : null)
                .entryRsi(featureStore != null ? featureStore.getRsi() : null)
                .entryMacdHistogram(featureStore != null ? featureStore.getMacdHistogram() : null)
                .entrySignedEr20(featureStore != null ? featureStore.getSignedEr20() : null)
                .entryRelativeVolume20(featureStore != null ? featureStore.getRelativeVolume20() : null)
                .entryPlusDi(featureStore != null ? featureStore.getPlusDI() : null)
                .entryMinusDi(featureStore != null ? featureStore.getMinusDI() : null)
                .entryEma20(featureStore != null ? featureStore.getEma20() : null)
                .entryEma50(featureStore != null ? featureStore.getEma50() : null)
                .entryEma200(featureStore != null ? featureStore.getEma200() : null)
                .entryEma50Slope(featureStore != null ? featureStore.getEma50Slope() : null)
                .entryEma200Slope(featureStore != null ? featureStore.getEma200Slope() : null)
                .entryCloseLocationValue(featureStore != null ? featureStore.getCloseLocationValue() : null)
                .entryIsBullishBreakout(featureStore != null ? featureStore.getIsBullishBreakout() : null)
                .entryIsBearishBreakout(featureStore != null ? featureStore.getIsBearishBreakout() : null)
                .biasTrendRegime(biasFeatureStore != null ? biasFeatureStore.getTrendRegime() : null)
                .biasAdx(biasFeatureStore != null ? biasFeatureStore.getAdx() : null)
                .biasAtr(biasFeatureStore != null ? biasFeatureStore.getAtr() : null)
                .biasRsi(biasFeatureStore != null ? biasFeatureStore.getRsi() : null)
                .biasMacdHistogram(biasFeatureStore != null ? biasFeatureStore.getMacdHistogram() : null)
                .biasSignedEr20(biasFeatureStore != null ? biasFeatureStore.getSignedEr20() : null)
                .biasPlusDi(biasFeatureStore != null ? biasFeatureStore.getPlusDI() : null)
                .biasMinusDi(biasFeatureStore != null ? biasFeatureStore.getMinusDI() : null)
                .biasEma50(biasFeatureStore != null ? biasFeatureStore.getEma50() : null)
                .biasEma200(biasFeatureStore != null ? biasFeatureStore.getEma200() : null)
                .biasEma200Slope(biasFeatureStore != null ? biasFeatureStore.getEma200Slope() : null)
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
                            .initialTrailingStopPrice(decision.getTrailingStopPrice())
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

        // Every live exit — SL, trailing stop, AND take-profit — goes through a
        // market order (see LiveTradingDecisionExecutorService.executeListenerClosePosition
        // → TradeService.binanceCloseLong/ShortPositionsMarketOrder). Previously this
        // branch exempted TAKE_PROFIT from slippage on the assumption TP was a resting
        // limit order, which systematically overstated backtest P&L on winning trades.
        // Applying slippage on every exit keeps sim results honest against live fills.
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

        BigDecimal pnlPercent = safe(position.getEntryQuoteQty()).compareTo(BigDecimal.ZERO) > 0
                ? pnlAmount.divide(position.getEntryQuoteQty(), 8, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

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
                if (decision.getTakeProfitPrice3() != null) {
                    position.setTakeProfitPrice(decision.getTakeProfitPrice3());
                } else if (decision.getTakeProfitPrice1() == null
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

        // ── Aggregate position-level price extremes to parent ─────────────────
        BigDecimal highestPrice = allPositions.stream()
                .map(BacktestTradePosition::getHighestPriceSinceEntry)
                .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                .max(BigDecimal::compareTo)
                .orElse(safe(trade.getAvgEntryPrice()));

        BigDecimal lowestPrice = allPositions.stream()
                .map(BacktestTradePosition::getLowestPriceSinceEntry)
                .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                .min(BigDecimal::compareTo)
                .orElse(safe(trade.getAvgEntryPrice()));

        // ── Gross PnL (before fees) ────────────────────────────────────────────
        BigDecimal grossPnlAmount = realizedPnlAmount.add(totalFeeAmount);

        trade.setTotalRemainingQty(totalRemainingQty);
        trade.setTotalExitQty(totalClosedQty);
        trade.setTotalExitQuoteQty(totalClosedQuote);
        trade.setRealizedPnlAmount(realizedPnlAmount);
        trade.setGrossPnlAmount(grossPnlAmount);
        trade.setTotalFeeAmount(totalFeeAmount);
        trade.setAvgExitPrice(avgExitPrice);
        trade.setHighestPriceDuringTrade(highestPrice);
        trade.setLowestPriceDuringTrade(lowestPrice);

        if (safe(trade.getTotalEntryQuoteQty()).compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pnlPercent = realizedPnlAmount
                    .divide(trade.getTotalEntryQuoteQty(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            trade.setRealizedPnlPercent(pnlPercent);
        }

        if (openCount == 0) {
            trade.setStatus(STATUS_CLOSED);
            trade.setExitTime(exitTime);

            // ── Close-time analytics (computed once at full close) ─────────────
            BigDecimal avgEntry = safe(trade.getAvgEntryPrice());
            BigDecimal totalQty = safe(trade.getTotalEntryQty());
            boolean isLong = "LONG".equalsIgnoreCase(trade.getSide());

            BigDecimal mfeAmount = isLong
                    ? highestPrice.subtract(avgEntry).multiply(totalQty).setScale(8, RoundingMode.HALF_UP)
                    : avgEntry.subtract(lowestPrice).multiply(totalQty).setScale(8, RoundingMode.HALF_UP);
            BigDecimal maeAmount = isLong
                    ? avgEntry.subtract(lowestPrice).multiply(totalQty).setScale(8, RoundingMode.HALF_UP)
                    : highestPrice.subtract(avgEntry).multiply(totalQty).setScale(8, RoundingMode.HALF_UP);

            BigDecimal mfeClamped = mfeAmount.max(BigDecimal.ZERO);
            BigDecimal maeClamped = maeAmount.max(BigDecimal.ZERO);
            trade.setMaxFavorableExcursionAmount(mfeClamped);
            trade.setMaxAdverseExcursionAmount(maeClamped);

            BigDecimal initialRisk = safe(trade.getInitialRiskAmount());
            if (initialRisk.compareTo(BigDecimal.ZERO) > 0) {
                trade.setMaxFavorableExcursionR(
                        mfeClamped.divide(initialRisk, 8, RoundingMode.HALF_UP));
                trade.setMaxAdverseExcursionR(
                        maeClamped.divide(initialRisk, 8, RoundingMode.HALF_UP));
                trade.setRealizedRMultiple(
                        realizedPnlAmount.divide(initialRisk, 8, RoundingMode.HALF_UP));
            }

            // ── Exit reason / final stop — from last position to close ────────
            allPositions.stream()
                    .filter(p -> p.getExitTime() != null)
                    .max(Comparator.comparing(BacktestTradePosition::getExitTime))
                    .ifPresent(p -> {
                        trade.setExitReason(p.getExitReason());
                        trade.setFinalStopLossPrice(p.getCurrentStopLossPrice());
                        trade.setLastTrailingStopPrice(p.getTrailingStopPrice());
                    });

            // ── Holding duration ──────────────────────────────────────────────
            if (trade.getEntryTime() != null) {
                long minutes = Duration.between(trade.getEntryTime(), exitTime).toMinutes();
                trade.setHoldingMinutes(minutes);
                int intervalMins = intervalToMinutes(trade.getInterval());
                if (intervalMins > 0) {
                    trade.setBarsHeld((int) (minutes / intervalMins));
                }
            }

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

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Returns the number of minutes in a candle interval string (e.g. "4h" → 240).
     * Returns 0 for unknown intervals (barsHeld will be left null).
     */
    private int intervalToMinutes(String interval) {
        if (interval == null) return 0;
        return switch (interval.toLowerCase()) {
            case "1m"  -> 1;
            case "3m"  -> 3;
            case "5m"  -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h"  -> 60;
            case "2h"  -> 120;
            case "4h"  -> 240;
            case "6h"  -> 360;
            case "8h"  -> 480;
            case "12h" -> 720;
            case "1d"  -> 1440;
            case "3d"  -> 4320;
            case "1w"  -> 10080;
            default    -> 0;
        };
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