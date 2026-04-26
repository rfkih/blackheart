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

        // Multi-trade routing — every position-mutating action is scoped to
        // the deciding strategy so concurrent strategies don't accidentally
        // operate on each other's open trades. Prefer the decision's
        // strategyCode (set by the orchestrator's entry/owner loop), then
        // the synthetic AccountStrategy's strategyCode.
        String strategyCode = resolveDecisionStrategyCode(context, decision);

        switch (decision.getDecisionType()) {
            case OPEN_LONG -> storePendingEntry(state, context, decision, "LONG", strategyCode);
            case OPEN_SHORT -> storePendingEntry(state, context, decision, "SHORT", strategyCode);
            case UPDATE_POSITION_MANAGEMENT -> updateOpenPositions(state, decision, strategyCode);
            case CLOSE_LONG, CLOSE_SHORT -> {
                if (context == null || context.getMarketData() == null) {
                    return;
                }

                closeAllOpenPositions(
                        backtestRun,
                        state,
                        strategyCode,
                        context.getMarketData().getClosePrice(),
                        decision.getExitReason() != null ? decision.getExitReason() : decision.getDecisionType().name(),
                        context.getMarketData().getEndTime()
                );
            }
            default -> {
            }
        }
    }

    private String resolveDecisionStrategyCode(EnrichedStrategyContext context, StrategyDecision decision) {
        if (decision != null && decision.getStrategyCode() != null && !decision.getStrategyCode().isBlank()) {
            return decision.getStrategyCode();
        }
        if (context != null && context.getAccountStrategy() != null
                && context.getAccountStrategy().getStrategyCode() != null) {
            return context.getAccountStrategy().getStrategyCode();
        }
        // Should never happen in current code — orchestrator always sets
        // either decision.strategyCode or context.accountStrategy.strategyCode.
        // If we hit this, downstream multi-trade routing falls through to the
        // legacy mirror, which under cap > 1 could operate on the wrong
        // strategy's trade. Surface the miswiring rather than swallow it.
        log.warn("Decision has no strategyCode and context.accountStrategy is null/missing-code "
                + "| decisionType={} — multi-trade routing will fall back to legacy mirror",
                decision == null ? "<null>" : decision.getDecisionType());
        return null;
    }

    private void storePendingEntry(
            BacktestState state,
            EnrichedStrategyContext context,
            StrategyDecision decision,
            String side,
            String strategyCode
    ) {
        // Per-strategy gates: a strategy can't queue a new entry if its own
        // trade is already open or it already has a pending entry queued.
        // Other strategies' state is irrelevant — under cap > 1 they can
        // open concurrently. Without this scoping, the second concurrent
        // entry signal on the same monitor bar was silently dropped.
        if (state.hasActiveTradeFor(strategyCode) || state.hasPendingEntryFor(strategyCode)) {
            return;
        }
        // Capture the strategy's resolved interval (Phase B2) so the trade
        // row gets stamped with THIS strategy's timeframe, not the run's
        // primary. context.getInterval() is set by buildAndEnrichContext
        // to ic.interval(), which is the per-strategy resolved value.
        String resolvedInterval = context != null ? context.getInterval() : null;
        state.setPendingEntryFor(strategyCode,
                new BacktestState.PendingEntry(decision, side,
                        context.getFeatureStore(), context.getBiasFeatureStore(),
                        resolvedInterval));
        log.debug("Backtest pending entry stored | side={} strategy={} interval={}",
                side, decision != null ? decision.getStrategyCode() : strategyCode,
                resolvedInterval);
    }

    public void fillPendingEntry(
            BacktestRun backtestRun,
            BacktestState state,
            BigDecimal openPrice,
            LocalDateTime entryTime
    ) {
        // Multi-trade aware: every strategy's pending entry fills independently
        // on the next bar. Snapshot the codes BEFORE iteration — openTrade
        // mutates the underlying map via state.addActiveTrade. Defensive
        // null-check: @Builder.Default initialises the map only when
        // BacktestState is built via the builder; a future caller using
        // the no-args constructor would leave it null.
        java.util.Map<String, BacktestState.PendingEntry> pendingMap =
                state.getPendingEntriesByStrategy();
        if (pendingMap == null || pendingMap.isEmpty()) {
            return;
        }
        java.util.List<String> strategyCodes = new java.util.ArrayList<>(pendingMap.keySet());
        for (String strategyCode : strategyCodes) {
            BacktestState.PendingEntry pending = state.getPendingEntryFor(strategyCode);
            if (pending == null) continue;

            state.clearPendingEntryFor(strategyCode);

            // Per-strategy guard: only block if THIS strategy already has
            // an active trade. Another strategy's open trade does not stop
            // this one from filling.
            if (state.hasActiveTradeFor(strategyCode)) {
                log.warn("Pending entry skipped | asset={} strategy={} reason=Active trade already exists",
                        backtestRun.getAsset(), strategyCode);
                continue;
            }

            // Cancel fill if the open price has gapped through the stop loss
            BigDecimal slPrice = pending.decision().getStopLossPrice();
            if (slPrice != null && slPrice.compareTo(BigDecimal.ZERO) > 0) {
                boolean gappedThroughSl = "LONG".equalsIgnoreCase(pending.side())
                        ? openPrice.compareTo(slPrice) <= 0
                        : openPrice.compareTo(slPrice) >= 0;
                if (gappedThroughSl) {
                    log.warn("Pending entry cancelled | asset={} strategy={} side={} reason=Gap through SL openPrice={} slPrice={}",
                            backtestRun.getAsset(), strategyCode, pending.side(), openPrice, slPrice);
                    continue;
                }
            }

            openTrade(backtestRun, state, pending.decision(), pending.side(),
                    pending.featureStore(), pending.biasFeatureStore(),
                    pending.interval(), openPrice, entryTime);
        }
    }

    /**
     * Suppression: this method is the trade-open aggregator — every parameter
     * is genuinely needed (decision, side, both feature stores, resolved
     * interval for stamping, fill price, fill timestamp). A wrapper record
     * would just shift the param noise to its constructor. Sonar S107 is a
     * heuristic for public APIs; this is a private helper.
     */
    @SuppressWarnings("java:S107")
    private void openTrade(
            BacktestRun backtestRun,
            BacktestState state,
            StrategyDecision decision,
            String tradeType,
            FeatureStore featureStore,
            FeatureStore biasFeatureStore,
            String resolvedInterval,
            BigDecimal rawEntryPrice,
            LocalDateTime entryTime
    ) {
        // Phase B2 — strategy may have fired on a different timeframe than
        // the run's primary interval. Prefer the resolved per-strategy
        // value; fall back to the run's primary so legacy / single-interval
        // runs keep their existing behaviour.
        String tradeInterval = (resolvedInterval != null && !resolvedInterval.isBlank())
                ? resolvedInterval
                : backtestRun.getInterval();
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
                .interval(tradeInterval)
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
                // Phase 2c — capture decision-time intent so trade
                // attribution at close can decompose realized P&L into
                // signal alpha + execution drift + sizing residual. For
                // backtest, intended_size mirrors notionalSize (LONG) or
                // positionSize (SHORT) since vol-targeting only runs live.
                .intendedEntryPrice(rawEntryPrice)
                .intendedSize("SHORT".equalsIgnoreCase(tradeType)
                        ? decision.getPositionSize()
                        : decision.getNotionalSize())
                .decisionTime(decision.getDecisionTime())
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
                            .interval(tradeInterval)
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
        // Phase B1 — register on the multi-trade map keyed by strategy code
        // so the orchestrator's concurrent-strategy cap can enforce. Method
        // also updates the legacy single-trade slot for back-compat with
        // the 30+ call sites that read state.activeTrade directly.
        String ownerCode = trade.getStrategyName() != null ? trade.getStrategyName() : "UNKNOWN";
        state.addActiveTrade(ownerCode, trade, new ArrayList<>(positions));

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

        // Multi-trade aware: the position knows its parent trade via tradeId,
        // and each trade has a strategyName. Resolve the owning strategy
        // from the position so refresh updates the RIGHT trade — not just
        // whatever the legacy mirror happens to point at.
        String strategyCode = findStrategyCodeForPosition(state, position);
        refreshParentTradeState(state, strategyCode, exitTime);
    }

    private void closeAllOpenPositions(
            BacktestRun backtestRun,
            BacktestState state,
            String strategyCode,
            BigDecimal exitPrice,
            String exitReason,
            LocalDateTime exitTime
    ) {
        // Multi-trade routing: close ONLY the deciding strategy's positions.
        // Reading state.getActiveTradePositions() (the legacy mirror) would
        // close whichever strategy's trade was opened most recently — could
        // be a different strategy entirely under cap > 1.
        List<BacktestTradePosition> scoped = scopedActivePositions(state, strategyCode);
        if (scoped.isEmpty()) {
            return;
        }

        // Snapshot the list — closeSinglePositionFromListener mutates the
        // underlying multi-trade map (removeActiveTrade on last leg) and
        // would ConcurrentModificationException a direct iteration.
        for (BacktestTradePosition position : new java.util.ArrayList<>(scoped)) {
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

    private void updateOpenPositions(BacktestState state, StrategyDecision decision, String strategyCode) {
        // Multi-trade routing: only update THIS strategy's positions.
        List<BacktestTradePosition> scoped = scopedActivePositions(state, strategyCode);
        if (scoped.isEmpty()) {
            return;
        }

        String targetRole = decision.getTargetPositionRole() == null || decision.getTargetPositionRole().isBlank()
                ? TARGET_ALL
                : decision.getTargetPositionRole().trim().toUpperCase();

        for (BacktestTradePosition position : scoped) {
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

    /**
     * Strategy-scoped position lookup: returns THIS strategy's active
     * positions when the multi-trade map is populated, falling back to the
     * legacy single-slot mirror only when no multi-trade entries exist
     * (single-strategy / pre-B1 path). Empty list, never null.
     */
    private List<BacktestTradePosition> scopedActivePositions(BacktestState state, String strategyCode) {
        if (state == null) return java.util.Collections.emptyList();
        java.util.Map<String, List<BacktestTradePosition>> byStrategy =
                state.getActiveTradePositionsByStrategy();
        if (byStrategy != null && !byStrategy.isEmpty() && strategyCode != null) {
            List<BacktestTradePosition> p = state.getActivePositionsFor(strategyCode);
            return p == null ? java.util.Collections.emptyList() : p;
        }
        List<BacktestTradePosition> legacy = state.getActiveTradePositions();
        return legacy == null ? java.util.Collections.emptyList() : legacy;
    }

    private BacktestTrade scopedActiveTrade(BacktestState state, String strategyCode) {
        if (state == null) return null;
        java.util.Map<String, BacktestTrade> byStrategy = state.getActiveTradesByStrategy();
        if (byStrategy != null && !byStrategy.isEmpty() && strategyCode != null) {
            return state.getActiveTradeFor(strategyCode);
        }
        return state.getActiveTrade();
    }

    /**
     * Reverse lookup: given a position, find which strategy owns it via the
     * position's parent {@code backtestTradeId}. Used when a listener-driven
     * close (stop / TP) needs to refresh the right parent trade and we
     * don't have a strategyCode in scope. Falls back to the trade's own
     * {@code strategyName} field when the multi-trade map doesn't contain
     * a matching entry.
     */
    private String findStrategyCodeForPosition(BacktestState state, BacktestTradePosition position) {
        if (state == null || position == null || position.getBacktestTradeId() == null) {
            return null;
        }
        java.util.Map<String, BacktestTrade> byStrategy = state.getActiveTradesByStrategy();
        if (byStrategy != null) {
            for (java.util.Map.Entry<String, BacktestTrade> e : byStrategy.entrySet()) {
                BacktestTrade t = e.getValue();
                if (t != null && position.getBacktestTradeId().equals(t.getBacktestTradeId())) {
                    return e.getKey();
                }
            }
        }
        // Fallback: the trade's strategyName, if its parent is the legacy mirror.
        BacktestTrade legacy = state.getActiveTrade();
        if (legacy != null && position.getBacktestTradeId().equals(legacy.getBacktestTradeId())) {
            return legacy.getStrategyName();
        }
        // Position's parent isn't in the active map AND isn't the legacy
        // mirror — would only happen on a double-close or a race condition
        // (parent removed before this position close fires). Surface the
        // anomaly so a real bug doesn't silently corrupt the legacy mirror's
        // trade via the fallback path in refreshParentTradeState.
        log.warn("Position has no resolvable owning strategy | positionId={} tradeId={} "
                + "— refreshParentTradeState will fall back to legacy mirror",
                position.getTradePositionId(), position.getBacktestTradeId());
        return null;
    }

    private void refreshParentTradeState(BacktestState state, String strategyCode, LocalDateTime exitTime) {
        // Multi-trade aware: refresh THIS strategy's trade + positions, not
        // the legacy mirror's most-recently-added pair. The legacy mirror
        // is updated as a side effect of state.removeActiveTrade when the
        // last leg closes.
        BacktestTrade trade = scopedActiveTrade(state, strategyCode);
        List<BacktestTradePosition> allPositions = scopedActivePositions(state, strategyCode);
        if (trade == null || allPositions.isEmpty()) {
            return;
        }

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

            // Phase B1 — clear the multi-trade slot (also refreshes the
            // legacy single-trade fields). When other strategies still
            // have open trades, the legacy mirror points at one of them.
            String ownerCode = trade.getStrategyName() != null ? trade.getStrategyName() : "UNKNOWN";
            state.removeActiveTrade(ownerCode);
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