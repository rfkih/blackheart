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
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestTradeExecutorService {

    private final BacktestPricingService backtestPricingService;
    private final BacktestFundingCostService backtestFundingCostService;

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_PARTIALLY_CLOSED = "PARTIALLY_CLOSED";

    private static final String SIDE_SHORT = "SHORT";

    // Exit-structure values double as position-role values for the entry leg
    // they produce (an EXIT_STRUCTURE_SINGLE entry yields a "SINGLE"-role
    // position, etc.) — the alignment is intentional in buildPositionPlan().
    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String EXIT_STRUCTURE_TP1_RUNNER = "TP1_RUNNER";
    private static final String EXIT_STRUCTURE_TP1_TP2_RUNNER = "TP1_TP2_RUNNER";
    private static final String EXIT_STRUCTURE_RUNNER_ONLY = "RUNNER_ONLY";
    private static final String POSITION_ROLE_RUNNER = "RUNNER";

    private static final String TARGET_ALL = "ALL";

    public void execute(
            BacktestRun backtestRun,
            BacktestState state,
            EnrichedStrategyContext context,
            StrategyDecision decision
    ) {
        if (ObjectUtils.isEmpty(decision)
                || ObjectUtils.isEmpty(decision.getDecisionType())
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
            case OPEN_SHORT -> storePendingEntry(state, context, decision, SIDE_SHORT, strategyCode);
            case UPDATE_POSITION_MANAGEMENT -> updateOpenPositions(state, decision, strategyCode);
            case CLOSE_LONG, CLOSE_SHORT -> {
                if (ObjectUtils.isEmpty(context) || ObjectUtils.isEmpty(context.getMarketData())) {
                    return;
                }

                closeAllOpenPositions(
                        backtestRun,
                        state,
                        strategyCode,
                        context.getMarketData().getClosePrice(),
                        ObjectUtils.isNotEmpty(decision.getExitReason()) ? decision.getExitReason() : decision.getDecisionType().name(),
                        context.getMarketData().getEndTime()
                );
            }
            default -> {
                // No-op: HOLD is filtered above, and any future DecisionType
                // we don't recognise should be ignored rather than crash the
                // backtest mid-loop.
            }
        }
    }

    private String resolveDecisionStrategyCode(EnrichedStrategyContext context, StrategyDecision decision) {
        if (ObjectUtils.isNotEmpty(decision) && StringUtils.hasText(decision.getStrategyCode())) {
            return decision.getStrategyCode();
        }
        if (ObjectUtils.isNotEmpty(context) && ObjectUtils.isNotEmpty(context.getAccountStrategy())
                && ObjectUtils.isNotEmpty(context.getAccountStrategy().getStrategyCode())) {
            return context.getAccountStrategy().getStrategyCode();
        }
        // Should never happen in current code — orchestrator always sets
        // either decision.strategyCode or context.accountStrategy.strategyCode.
        // If we hit this, downstream multi-trade routing falls through to the
        // legacy mirror, which under cap > 1 could operate on the wrong
        // strategy's trade. Surface the miswiring rather than swallow it.
        log.warn("Decision has no strategyCode and context.accountStrategy is null/missing-code "
                + "| decisionType={} — multi-trade routing will fall back to legacy mirror",
                ObjectUtils.isEmpty(decision) ? "<null>" : decision.getDecisionType());
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
        // Capture the strategy's resolved interval so the trade row gets
        // stamped with its actual timeframe. context.getInterval() is set by
        // buildAndEnrichContext to ic.interval(), the per-strategy value.
        String resolvedInterval = ObjectUtils.isNotEmpty(context) ? context.getInterval() : null;
        state.setPendingEntryFor(strategyCode,
                new BacktestState.PendingEntry(decision, side,
                        context.getFeatureStore(), context.getBiasFeatureStore(),
                        resolvedInterval));
        log.debug("Backtest pending entry stored | side={} strategy={} interval={}",
                side, ObjectUtils.isNotEmpty(decision) ? decision.getStrategyCode() : strategyCode,
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
        if (CollectionUtils.isEmpty(pendingMap)) {
            return;
        }
        List<String> strategyCodes = new ArrayList<>(pendingMap.keySet());
        for (String strategyCode : strategyCodes) {
            tryFillPendingEntry(backtestRun, state, openPrice, entryTime, strategyCode);
        }
    }

    private void tryFillPendingEntry(
            BacktestRun backtestRun,
            BacktestState state,
            BigDecimal openPrice,
            LocalDateTime entryTime,
            String strategyCode
    ) {
        BacktestState.PendingEntry pending = state.getPendingEntryFor(strategyCode);
        if (ObjectUtils.isEmpty(pending)) return;

        state.clearPendingEntryFor(strategyCode);

        // Per-strategy guard: only block if THIS strategy already has an
        // active trade. Another strategy's open trade does not stop this one
        // from filling.
        if (state.hasActiveTradeFor(strategyCode)) {
            log.warn("Pending entry skipped | asset={} strategy={} reason=Active trade already exists",
                    backtestRun.getAsset(), strategyCode);
            return;
        }

        if (gappedThroughStopLoss(pending, openPrice)) {
            log.warn("Pending entry cancelled | asset={} strategy={} side={} reason=Gap through SL openPrice={} slPrice={}",
                    backtestRun.getAsset(), strategyCode, pending.side(),
                    openPrice, pending.decision().getStopLossPrice());
            return;
        }

        openTrade(backtestRun, state, pending.decision(), pending.side(),
                pending.featureStore(), pending.biasFeatureStore(),
                pending.interval(), openPrice, entryTime);
    }

    private boolean gappedThroughStopLoss(BacktestState.PendingEntry pending, BigDecimal openPrice) {
        BigDecimal slPrice = pending.decision().getStopLossPrice();
        if (ObjectUtils.isEmpty(slPrice) || slPrice.compareTo(BigDecimal.ZERO) <= 0) return false;
        return "LONG".equalsIgnoreCase(pending.side())
                ? openPrice.compareTo(slPrice) <= 0
                : openPrice.compareTo(slPrice) >= 0;
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
        // Prefer the strategy's resolved interval over the run's primary so
        // multi-timeframe trades are stamped with the correct timeframe.
        String tradeInterval = StringUtils.hasText(resolvedInterval)
                ? resolvedInterval
                : backtestRun.getInterval();
        // Resolve the USDT-denominated trade amount BEFORE applying slippage:
        // strategies size off the raw fill price, not the slipped one. SHORT
        // decisions set positionSize in BTC; we convert to USDT here.
        BigDecimal requestedQuoteAmount = resolveRequestedQuoteAmount(decision, rawEntryPrice);

        if (requestedQuoteAmount.compareTo(BigDecimal.ZERO) <= 0
                || safe(rawEntryPrice).compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Backtest {} rejected | asset={} reason=Invalid size or entry price",
                    tradeType, backtestRun.getAsset());
            return;
        }

        BigDecimal entryPrice = backtestPricingService.applyEntrySlippage(
                rawEntryPrice, backtestRun.getSlippagePct(), tradeType);
        BigDecimal slippagePerUnit = computeSlippagePerUnit(rawEntryPrice, entryPrice, tradeType);

        BigDecimal entryFee = requestedQuoteAmount
                .multiply(safe(backtestRun.getFeePct()))
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal totalQty = requestedQuoteAmount.subtract(entryFee)
                .divide(entryPrice, 12, RoundingMode.DOWN);

        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Backtest {} rejected | asset={} reason=Calculated quantity is zero",
                    tradeType, backtestRun.getAsset());
            return;
        }
        if (safe(state.getCashBalance()).compareTo(requestedQuoteAmount) < 0) {
            log.warn("Backtest {} rejected | asset={} reason=Insufficient synthetic cash balance",
                    tradeType, backtestRun.getAsset());
            return;
        }

        InitialRisk risk = computeInitialRisk(entryPrice, decision.getStopLossPrice(),
                totalQty, requestedQuoteAmount, tradeType);

        BacktestTrade trade = buildBacktestTradeRow(backtestRun, decision, tradeType, tradeInterval,
                featureStore, biasFeatureStore, entryPrice, rawEntryPrice, totalQty,
                requestedQuoteAmount, entryFee, slippagePerUnit, risk, entryTime);

        List<PlannedPosition> plan = buildPositionPlan(totalQty, requestedQuoteAmount, entryFee, decision);
        List<BacktestTradePosition> positions = buildOpenedPositions(backtestRun, decision, tradeType,
                tradeInterval, entryPrice, entryTime, trade.getBacktestTradeId(), plan);

        state.setCashBalance(safe(state.getCashBalance()).subtract(requestedQuoteAmount));
        // Register on the multi-trade map keyed by strategy code so the
        // orchestrator's concurrent-strategy cap can enforce. Also updates
        // the legacy single-trade slot for the call sites that read
        // state.activeTrade directly.
        String ownerCode = ObjectUtils.isNotEmpty(trade.getStrategyName()) ? trade.getStrategyName() : "UNKNOWN";
        state.addActiveTrade(ownerCode, trade, new ArrayList<>(positions));

        log.info("Backtest {} opened | timeOpen={} qty={} quote={} fee={} positions={}",
                tradeType,
                trade.getEntryTime(),
                totalQty,
                requestedQuoteAmount,
                entryFee,
                positions.size());
    }

    /**
     * Slippage delta: positive = worse fill than raw price (always adverse).
     * LONG: entryPrice &gt; rawEntryPrice → slippage = entryPrice - rawEntryPrice.
     * SHORT: entryPrice &lt; rawEntryPrice → slippage = rawEntryPrice - entryPrice.
     */
    private BigDecimal computeSlippagePerUnit(BigDecimal rawEntryPrice, BigDecimal entryPrice, String tradeType) {
        BigDecimal delta = "LONG".equalsIgnoreCase(tradeType)
                ? entryPrice.subtract(rawEntryPrice)
                : rawEntryPrice.subtract(entryPrice);
        return delta.max(BigDecimal.ZERO);
    }

    private InitialRisk computeInitialRisk(
            BigDecimal entryPrice, BigDecimal slPrice, BigDecimal totalQty,
            BigDecimal requestedQuoteAmount, String tradeType
    ) {
        BigDecimal perUnit = BigDecimal.ZERO;
        if (ObjectUtils.isNotEmpty(slPrice) && slPrice.compareTo(BigDecimal.ZERO) > 0) {
            perUnit = "LONG".equalsIgnoreCase(tradeType)
                    ? entryPrice.subtract(slPrice)
                    : slPrice.subtract(entryPrice);
            if (perUnit.compareTo(BigDecimal.ZERO) < 0) {
                perUnit = BigDecimal.ZERO;
            }
        }
        BigDecimal amount = perUnit.multiply(totalQty).setScale(8, RoundingMode.HALF_UP);
        BigDecimal percent = requestedQuoteAmount.compareTo(BigDecimal.ZERO) > 0
                ? amount.divide(requestedQuoteAmount, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        return new InitialRisk(perUnit, amount, percent);
    }

    private record InitialRisk(BigDecimal perUnit, BigDecimal amount, BigDecimal percent) {}

    @SuppressWarnings("java:S107")
    private BacktestTrade buildBacktestTradeRow(
            BacktestRun backtestRun,
            StrategyDecision decision,
            String tradeType,
            String tradeInterval,
            FeatureStore featureStore,
            FeatureStore biasFeatureStore,
            BigDecimal entryPrice,
            BigDecimal rawEntryPrice,
            BigDecimal totalQty,
            BigDecimal requestedQuoteAmount,
            BigDecimal entryFee,
            BigDecimal slippagePerUnit,
            InitialRisk risk,
            LocalDateTime entryTime
    ) {
        // Caller invariant: openTrade has already dereferenced `decision`
        // (resolveRequestedQuoteAmount, getStopLossPrice) so it is non-null
        // by the time we land here. Asserted explicitly so Sonar's flow
        // analysis stops propagating the nullability inferred from
        // resolveStrategyName's defensive null check.
        Objects.requireNonNull(decision, "decision must not be null");

        BigDecimal slippageAmount = slippagePerUnit.multiply(totalQty).setScale(8, RoundingMode.HALF_UP);
        BigDecimal slippagePercent = requestedQuoteAmount.compareTo(BigDecimal.ZERO) > 0
                ? slippageAmount.divide(requestedQuoteAmount, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        BacktestTrade.BacktestTradeBuilder b = BacktestTrade.builder()
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
                .initialStopLossPrice(decision.getStopLossPrice())
                .initialTrailingStopPrice(decision.getTrailingStopPrice())
                .initialRiskPerUnit(risk.perUnit())
                .initialRiskAmount(risk.amount())
                .initialRiskPercent(risk.percent())
                .grossPnlAmount(BigDecimal.ZERO)
                .realizedPnlAmount(BigDecimal.ZERO)
                .realizedPnlPercent(BigDecimal.ZERO)
                .totalFeeAmount(entryFee)
                .totalFeeCurrency("USDT")
                .slippageAmount(slippageAmount)
                .slippagePercent(slippagePercent)
                .highestPriceDuringTrade(entryPrice)
                .lowestPriceDuringTrade(entryPrice)
                .entrySignalScore(decision.getSignalScore())
                .entryConfidenceScore(decision.getConfidenceScore())
                .exitReason(null)
                .entryTime(entryTime)
                .exitTime(null)
                // Phase 2c — capture decision-time intent so trade attribution
                // at close can decompose realized P&L into signal alpha +
                // execution drift + sizing residual. For backtest,
                // intended_size mirrors notionalSize (LONG) or positionSize
                // (SHORT) since vol-targeting only runs live.
                .intendedEntryPrice(rawEntryPrice)
                .intendedSize(SIDE_SHORT.equalsIgnoreCase(tradeType)
                        ? decision.getPositionSize()
                        : decision.getNotionalSize())
                .decisionTime(decision.getDecisionTime());

        applyEntryFeatures(b, featureStore);
        applyBiasFeatures(b, biasFeatureStore);
        return b.build();
    }

    private void applyEntryFeatures(BacktestTrade.BacktestTradeBuilder b, FeatureStore fs) {
        if (ObjectUtils.isEmpty(fs)) return;
        b.entryTrendRegime(fs.getTrendRegime())
                .entryAdx(fs.getAdx())
                .entryAtr(fs.getAtr())
                .entryRsi(fs.getRsi())
                .entryMacdHistogram(fs.getMacdHistogram())
                .entrySignedEr20(fs.getSignedEr20())
                .entryRelativeVolume20(fs.getRelativeVolume20())
                .entryPlusDi(fs.getPlusDI())
                .entryMinusDi(fs.getMinusDI())
                .entryEma20(fs.getEma20())
                .entryEma50(fs.getEma50())
                .entryEma200(fs.getEma200())
                .entryEma50Slope(fs.getEma50Slope())
                .entryEma200Slope(fs.getEma200Slope())
                .entryCloseLocationValue(fs.getCloseLocationValue())
                .entryIsBullishBreakout(fs.getIsBullishBreakout())
                .entryIsBearishBreakout(fs.getIsBearishBreakout());
    }

    private void applyBiasFeatures(BacktestTrade.BacktestTradeBuilder b, FeatureStore fs) {
        if (fs == null) return;
        b.biasTrendRegime(fs.getTrendRegime())
                .biasAdx(fs.getAdx())
                .biasAtr(fs.getAtr())
                .biasRsi(fs.getRsi())
                .biasMacdHistogram(fs.getMacdHistogram())
                .biasSignedEr20(fs.getSignedEr20())
                .biasPlusDi(fs.getPlusDI())
                .biasMinusDi(fs.getMinusDI())
                .biasEma50(fs.getEma50())
                .biasEma200(fs.getEma200())
                .biasEma200Slope(fs.getEma200Slope());
    }

    @SuppressWarnings("java:S107")
    private List<BacktestTradePosition> buildOpenedPositions(
            BacktestRun backtestRun,
            StrategyDecision decision,
            String tradeType,
            String tradeInterval,
            BigDecimal entryPrice,
            LocalDateTime entryTime,
            UUID tradeId,
            List<PlannedPosition> plan
    ) {
        List<BacktestTradePosition> positions = new ArrayList<>();
        for (PlannedPosition planned : plan) {
            positions.add(buildOpenedPosition(backtestRun, decision, tradeType, tradeInterval,
                    entryPrice, entryTime, tradeId, planned));
        }
        return positions;
    }

    @SuppressWarnings("java:S107")
    private BacktestTradePosition buildOpenedPosition(
            BacktestRun backtestRun,
            StrategyDecision decision,
            String tradeType,
            String tradeInterval,
            BigDecimal entryPrice,
            LocalDateTime entryTime,
            UUID tradeId,
            PlannedPosition planned
    ) {
        return BacktestTradePosition.builder()
                .tradePositionId(UUID.randomUUID())
                .backtestTradeId(tradeId)
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
                .lowestPriceSinceEntry(SIDE_SHORT.equalsIgnoreCase(tradeType) ? entryPrice : null)
                .realizedPnlAmount(BigDecimal.ZERO)
                .realizedPnlPercent(BigDecimal.ZERO)
                .exitReason(null)
                .entryTime(entryTime)
                .exitTime(null)
                .build();
    }

    public void closeSinglePositionFromListener(
            BacktestRun backtestRun,
            BacktestState state,
            BacktestTradePosition position,
            BigDecimal exitPrice,
            String exitReason,
            LocalDateTime exitTime
    ) {
        if (ObjectUtils.isEmpty(position) || !STATUS_OPEN.equalsIgnoreCase(position.getStatus())) {
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

        // Funding cost: when the run pins a flat rate (V22 stub, tests, what-if
        // sweeps) honor it bit-identically; otherwise sum actual settlement
        // events from funding_rate_history (Phase 4.6). Non-perp symbols and
        // cold-start symbols silently return zero from the per-event path.
        BigDecimal flatRate = backtestRun.getFundingRateBpsPer8h();
        BigDecimal fundingCost;
        if (ObjectUtils.isNotEmpty(flatRate) && flatRate.signum() != 0) {
            fundingCost = backtestFundingCostService.compute(
                    safe(position.getEntryQuoteQty()),
                    position.getSide(),
                    position.getEntryTime(),
                    exitTime,
                    flatRate
            );
        } else {
            fundingCost = backtestFundingCostService.computePerEvent(
                    safe(position.getEntryQuoteQty()),
                    position.getSide(),
                    backtestRun.getAsset(),
                    position.getEntryTime(),
                    exitTime
            );
        }

        BigDecimal pnlAmount = calculatePLAmount(
                entryPrice,
                cleanExitPrice,
                remainingQty,
                position.getSide()
        ).subtract(safe(position.getEntryFee()))
         .subtract(exitFee)
         .subtract(fundingCost);

        BigDecimal pnlPercent = safe(position.getEntryQuoteQty()).compareTo(BigDecimal.ZERO) > 0
                ? pnlAmount.divide(position.getEntryQuoteQty(), 8, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        position.setExitPrice(cleanExitPrice);
        position.setExitExecutedQty(remainingQty);
        position.setExitExecutedQuoteQty(exitQuoteQty);
        position.setExitFee(exitFee);
        // Mirror entry_fee_currency on close — backtest fees are always in
        // the quote currency of the pair (USDT for BTCUSDT). Without this
        // every closed position has a null exit_fee_currency.
        position.setExitFeeCurrency(ObjectUtils.isNotEmpty(position.getEntryFeeCurrency())
                ? position.getEntryFeeCurrency() : "USDT");
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

        String targetRole = !StringUtils.hasText(decision.getTargetPositionRole())
                ? TARGET_ALL
                : decision.getTargetPositionRole().trim().toUpperCase();

        for (BacktestTradePosition position : scoped) {
            applyPositionManagementUpdate(position, decision, targetRole);
        }
    }

    private void applyPositionManagementUpdate(
            BacktestTradePosition position, StrategyDecision decision, String targetRole
    ) {
        if (!STATUS_OPEN.equalsIgnoreCase(position.getStatus())) return;
        if (!matchesTargetRole(position, targetRole)) return;

        if (ObjectUtils.isNotEmpty(decision.getStopLossPrice())) {
            position.setCurrentStopLossPrice(decision.getStopLossPrice());
        }
        if (ObjectUtils.isNotEmpty(decision.getTrailingStopPrice())) {
            position.setTrailingStopPrice(decision.getTrailingStopPrice());
        }

        String role = ObjectUtils.isEmpty(position.getPositionRole())
                ? ""
                : position.getPositionRole().trim().toUpperCase();
        applyTakeProfitForRole(position, decision, role);
    }

    private boolean matchesTargetRole(BacktestTradePosition position, String targetRole) {
        if (TARGET_ALL.equals(targetRole)) return true;
        return ObjectUtils.isNotEmpty(position.getPositionRole())
                && targetRole.equalsIgnoreCase(position.getPositionRole());
    }

    private void applyTakeProfitForRole(
            BacktestTradePosition position, StrategyDecision decision, String role
    ) {
        if (EXIT_STRUCTURE_SINGLE.equals(role) || "TP1".equals(role)) {
            if (ObjectUtils.isNotEmpty(decision.getTakeProfitPrice1())) {
                position.setTakeProfitPrice(decision.getTakeProfitPrice1());
            }
        } else if ("TP2".equals(role)) {
            if (ObjectUtils.isNotEmpty(decision.getTakeProfitPrice2())) {
                position.setTakeProfitPrice(decision.getTakeProfitPrice2());
            }
        } else if (POSITION_ROLE_RUNNER.equals(role)) {
            applyRunnerTakeProfit(position, decision);
        }
    }

    private void applyRunnerTakeProfit(BacktestTradePosition position, StrategyDecision decision) {
        if (ObjectUtils.isNotEmpty(decision.getTakeProfitPrice3())) {
            position.setTakeProfitPrice(decision.getTakeProfitPrice3());
        } else if (ObjectUtils.isEmpty(decision.getTakeProfitPrice1())
                && ObjectUtils.isEmpty(decision.getTakeProfitPrice2())
                && ObjectUtils.isEmpty(decision.getTakeProfitPrice3())) {
            position.setTakeProfitPrice(null);
        }
    }

    /**
     * Strategy-scoped position lookup: returns THIS strategy's active
     * positions when the multi-trade map is populated, falling back to the
     * legacy single-slot mirror only when no multi-trade entries exist
     * (single-strategy / pre-B1 path). Empty list, never null.
     */
    private List<BacktestTradePosition> scopedActivePositions(BacktestState state, String strategyCode) {
        if (ObjectUtils.isEmpty(state)) return java.util.Collections.emptyList();
        java.util.Map<String, List<BacktestTradePosition>> byStrategy =
                state.getActiveTradePositionsByStrategy();
        if (!CollectionUtils.isEmpty(byStrategy) && ObjectUtils.isNotEmpty(strategyCode)) {
            List<BacktestTradePosition> p = state.getActivePositionsFor(strategyCode);
            return ObjectUtils.isEmpty(p) ? java.util.Collections.emptyList() : p;
        }
        List<BacktestTradePosition> legacy = state.getActiveTradePositions();
        return ObjectUtils.isEmpty(legacy) ? java.util.Collections.emptyList() : legacy;
    }

    private BacktestTrade scopedActiveTrade(BacktestState state, String strategyCode) {
        if (ObjectUtils.isEmpty(state)) return null;
        java.util.Map<String, BacktestTrade> byStrategy = state.getActiveTradesByStrategy();
        if (!CollectionUtils.isEmpty(byStrategy) && ObjectUtils.isNotEmpty(strategyCode)) {
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
        if (ObjectUtils.isEmpty(state) || ObjectUtils.isEmpty(position) || ObjectUtils.isEmpty(position.getBacktestTradeId())) {
            return null;
        }
        java.util.Map<String, BacktestTrade> byStrategy = state.getActiveTradesByStrategy();
        if (ObjectUtils.isNotEmpty(byStrategy)) {
            for (java.util.Map.Entry<String, BacktestTrade> e : byStrategy.entrySet()) {
                BacktestTrade t = e.getValue();
                if (ObjectUtils.isNotEmpty(t) && position.getBacktestTradeId().equals(t.getBacktestTradeId())) {
                    return e.getKey();
                }
            }
        }
        // Fallback: the trade's strategyName, if its parent is the legacy mirror.
        BacktestTrade legacy = state.getActiveTrade();
        if (ObjectUtils.isNotEmpty(legacy) && position.getBacktestTradeId().equals(legacy.getBacktestTradeId())) {
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

    /**
     * Pick the most representative exit reason across a multi-leg trade's
     * positions. Priority — what the trader actually cares about first:
     *
     * <ol>
     *   <li>{@code TAKE_PROFIT} — any leg that hit its TP. A TP1+RUNNER trade
     *       where TP1 hit and the runner later trailed out is fundamentally a
     *       "took profit" trade; the runner trail is a P&L-protection
     *       mechanism, not the primary exit story.</li>
     *   <li>{@code TRAILING_STOP} — any leg trailed out without a TP hit
     *       happening first. The trade ran in our favour but reversed.</li>
     *   <li>{@code STOP_LOSS} — original stop hit on any leg.</li>
     *   <li>Fallback to the latest-closed leg's exit reason (preserves the
     *       legacy single-leg behaviour for SINGLE-structure trades).</li>
     * </ol>
     *
     * <p>Listener-side reason names are matched case-insensitively and
     * substring-tolerant so future synonyms (e.g. {@code TP_HIT}) are caught.
     */
    private String aggregateExitReason(List<BacktestTradePosition> positions) {
        if (CollectionUtils.isEmpty(positions)) return null;

        boolean anyTp = false;
        boolean anyTrail = false;
        boolean anyStop = false;
        for (BacktestTradePosition p : positions) {
            String r = p.getExitReason();
            if (ObjectUtils.isEmpty(r)) continue;
            String upper = r.toUpperCase();
            if (upper.contains("TAKE_PROFIT") || upper.contains("TP_HIT")) anyTp = true;
            else if (upper.contains("TRAILING_STOP")) anyTrail = true;
            else if (upper.contains("STOP_LOSS") || upper.contains("SL_HIT")) anyStop = true;
        }
        if (anyTp) return "TAKE_PROFIT";
        if (anyTrail) return "TRAILING_STOP";
        if (anyStop) return "STOP_LOSS";

        // Nothing matched the standard set — fall through to the legacy
        // last-leg behaviour (e.g. BACKTEST_END, MANUAL_CLOSE).
        return positions.stream()
                .filter(p -> ObjectUtils.isNotEmpty(p.getExitTime()))
                .max(Comparator.comparing(BacktestTradePosition::getExitTime))
                .map(BacktestTradePosition::getExitReason)
                .orElse(null);
    }

    private void refreshParentTradeState(BacktestState state, String strategyCode, LocalDateTime exitTime) {
        // Multi-trade aware: refresh THIS strategy's trade + positions, not
        // the legacy mirror's most-recently-added pair. The legacy mirror
        // is updated as a side effect of state.removeActiveTrade when the
        // last leg closes.
        BacktestTrade trade = scopedActiveTrade(state, strategyCode);
        List<BacktestTradePosition> allPositions = scopedActivePositions(state, strategyCode);
        if (ObjectUtils.isEmpty(trade) || CollectionUtils.isEmpty(allPositions)) {
            return;
        }

        TradeAggregates agg = aggregatePositions(allPositions, trade);
        applyAggregatesToTrade(trade, agg);

        if (agg.openCount() == 0) {
            finalizeClosedTrade(state, trade, allPositions, agg, exitTime);
        } else if (agg.openCount() < allPositions.size()) {
            trade.setStatus(STATUS_PARTIALLY_CLOSED);
        } else {
            trade.setStatus(STATUS_OPEN);
        }
    }

    private TradeAggregates aggregatePositions(List<BacktestTradePosition> allPositions, BacktestTrade trade) {
        BigDecimal totalRemainingQty = sumPositions(allPositions, BacktestTradePosition::getRemainingQty);
        BigDecimal realizedPnlAmount = sumPositions(allPositions, BacktestTradePosition::getRealizedPnlAmount);
        BigDecimal totalFeeAmount = allPositions.stream()
                .map(p -> safe(p.getEntryFee()).add(safe(p.getExitFee())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalClosedQty = sumPositions(allPositions, BacktestTradePosition::getExitExecutedQty);
        BigDecimal totalClosedQuote = sumPositions(allPositions, BacktestTradePosition::getExitExecutedQuoteQty);
        BigDecimal avgExitPrice = totalClosedQty.compareTo(BigDecimal.ZERO) > 0
                ? totalClosedQuote.divide(totalClosedQty, 8, RoundingMode.HALF_UP)
                : null;
        long openCount = allPositions.stream()
                .filter(p -> STATUS_OPEN.equalsIgnoreCase(p.getStatus()))
                .count();
        BigDecimal highestPrice = allPositions.stream()
                .map(BacktestTradePosition::getHighestPriceSinceEntry)
                .filter(p -> ObjectUtils.isNotEmpty(p) && p.compareTo(BigDecimal.ZERO) > 0)
                .max(BigDecimal::compareTo)
                .orElse(safe(trade.getAvgEntryPrice()));
        BigDecimal lowestPrice = allPositions.stream()
                .map(BacktestTradePosition::getLowestPriceSinceEntry)
                .filter(p -> ObjectUtils.isNotEmpty(p) && p.compareTo(BigDecimal.ZERO) > 0)
                .min(BigDecimal::compareTo)
                .orElse(safe(trade.getAvgEntryPrice()));
        BigDecimal grossPnlAmount = realizedPnlAmount.add(totalFeeAmount);
        return new TradeAggregates(totalRemainingQty, totalClosedQty, totalClosedQuote,
                realizedPnlAmount, totalFeeAmount, grossPnlAmount, avgExitPrice,
                highestPrice, lowestPrice, openCount);
    }

    private BigDecimal sumPositions(
            List<BacktestTradePosition> positions,
            java.util.function.Function<BacktestTradePosition, BigDecimal> getter
    ) {
        return positions.stream().map(getter).map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void applyAggregatesToTrade(BacktestTrade trade, TradeAggregates agg) {
        trade.setTotalRemainingQty(agg.totalRemainingQty());
        trade.setTotalExitQty(agg.totalClosedQty());
        trade.setTotalExitQuoteQty(agg.totalClosedQuote());
        trade.setRealizedPnlAmount(agg.realizedPnlAmount());
        trade.setGrossPnlAmount(agg.grossPnlAmount());
        trade.setTotalFeeAmount(agg.totalFeeAmount());
        trade.setAvgExitPrice(agg.avgExitPrice());
        trade.setHighestPriceDuringTrade(agg.highestPrice());
        trade.setLowestPriceDuringTrade(agg.lowestPrice());

        if (safe(trade.getTotalEntryQuoteQty()).compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pnlPercent = agg.realizedPnlAmount()
                    .divide(trade.getTotalEntryQuoteQty(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            trade.setRealizedPnlPercent(pnlPercent);
        }
    }

    private void finalizeClosedTrade(
            BacktestState state,
            BacktestTrade trade,
            List<BacktestTradePosition> allPositions,
            TradeAggregates agg,
            LocalDateTime exitTime
    ) {
        trade.setStatus(STATUS_CLOSED);
        trade.setExitTime(exitTime);

        applyExcursionAnalytics(trade, agg);
        applyExitReasonAndFinalStop(trade, allPositions);
        applyHoldingDuration(trade, exitTime);

        state.getCompletedTrades().add(trade);
        state.getCompletedTradePositions().addAll(new ArrayList<>(allPositions));

        // Clear the multi-trade slot (also refreshes legacy single-trade
        // fields). When other strategies still have open trades, the legacy
        // mirror points at one of them.
        String ownerCode = ObjectUtils.isNotEmpty(trade.getStrategyName()) ? trade.getStrategyName() : "UNKNOWN";
        state.removeActiveTrade(ownerCode);
    }

    private void applyExcursionAnalytics(BacktestTrade trade, TradeAggregates agg) {
        BigDecimal avgEntry = safe(trade.getAvgEntryPrice());
        BigDecimal totalQty = safe(trade.getTotalEntryQty());
        boolean isLong = "LONG".equalsIgnoreCase(trade.getSide());

        BigDecimal mfeAmount = isLong
                ? agg.highestPrice().subtract(avgEntry).multiply(totalQty).setScale(8, RoundingMode.HALF_UP)
                : avgEntry.subtract(agg.lowestPrice()).multiply(totalQty).setScale(8, RoundingMode.HALF_UP);
        BigDecimal maeAmount = isLong
                ? avgEntry.subtract(agg.lowestPrice()).multiply(totalQty).setScale(8, RoundingMode.HALF_UP)
                : agg.highestPrice().subtract(avgEntry).multiply(totalQty).setScale(8, RoundingMode.HALF_UP);

        BigDecimal mfeClamped = mfeAmount.max(BigDecimal.ZERO);
        BigDecimal maeClamped = maeAmount.max(BigDecimal.ZERO);
        trade.setMaxFavorableExcursionAmount(mfeClamped);
        trade.setMaxAdverseExcursionAmount(maeClamped);

        BigDecimal initialRisk = safe(trade.getInitialRiskAmount());
        if (initialRisk.compareTo(BigDecimal.ZERO) > 0) {
            trade.setMaxFavorableExcursionR(mfeClamped.divide(initialRisk, 8, RoundingMode.HALF_UP));
            trade.setMaxAdverseExcursionR(maeClamped.divide(initialRisk, 8, RoundingMode.HALF_UP));
            trade.setRealizedRMultiple(agg.realizedPnlAmount().divide(initialRisk, 8, RoundingMode.HALF_UP));
        }
    }

    /**
     * Aggregate exit reason across legs with priority TAKE_PROFIT >
     * TRAILING_STOP > STOP_LOSS > anything else. Picking only the last-closed
     * leg's reason erased the TP hit on TP1+RUNNER structures (TP1 closes
     * first as TAKE_PROFIT, the runner trails out later — the parent ended
     * up labeled TRAILING_STOP even when the trade was profitable on TP1).
     * Final-stop / last-trailing fields still come from the latest-closed
     * leg, since those describe the closing state of the trade as a whole.
     */
    private void applyExitReasonAndFinalStop(BacktestTrade trade, List<BacktestTradePosition> allPositions) {
        trade.setExitReason(aggregateExitReason(allPositions));
        allPositions.stream()
                .filter(p -> p.getExitTime() != null)
                .max(Comparator.comparing(BacktestTradePosition::getExitTime))
                .ifPresent(p -> {
                    trade.setFinalStopLossPrice(p.getCurrentStopLossPrice());
                    trade.setLastTrailingStopPrice(p.getTrailingStopPrice());
                });
    }

    private void applyHoldingDuration(BacktestTrade trade, LocalDateTime exitTime) {
        if (trade.getEntryTime() == null) return;
        long minutes = Duration.between(trade.getEntryTime(), exitTime).toMinutes();
        trade.setHoldingMinutes(minutes);
        int intervalMins = intervalToMinutes(trade.getInterval());
        if (intervalMins > 0) {
            trade.setBarsHeld((int) (minutes / intervalMins));
        }
    }

    private record TradeAggregates(
            BigDecimal totalRemainingQty,
            BigDecimal totalClosedQty,
            BigDecimal totalClosedQuote,
            BigDecimal realizedPnlAmount,
            BigDecimal totalFeeAmount,
            BigDecimal grossPnlAmount,
            BigDecimal avgExitPrice,
            BigDecimal highestPrice,
            BigDecimal lowestPrice,
            long openCount
    ) {}

    /**
     * Resolve the USDT-denominated quote amount for a trade decision.
     *
     * <p>Strategies set sizing on the decision in one of two units, matching
     * how live trading consumes them:
     * <ul>
     *   <li><b>{@code notionalSize}</b> — USDT (quote-currency) amount.
     *       Set by LONG entries via
     *       {@link StrategyHelper#calculateEntryNotional(EnrichedStrategyContext, String)}.
     *       Live's {@code executeOpenLong} reads this and checks against the
     *       USDT portfolio balance.</li>
     *   <li><b>{@code positionSize}</b> — base-asset (e.g. BTC) quantity.
     *       Set by SHORT entries via
     *       {@link StrategyHelper#calculateShortPositionSize(EnrichedStrategyContext)}.
     *       Live's {@code executeOpenShort} reads this and checks against the
     *       BTC portfolio balance.</li>
     * </ul>
     *
     * <p>The backtest's internal cash math is USDT-denominated, so we convert
     * a base-quantity {@code positionSize} to USDT via the fill price. Without
     * this conversion, a SHORT decision setting {@code positionSize=0.2} (BTC)
     * was being interpreted as {@code 0.2 USDT}, producing microscopic
     * positions that silently looked like "no shorts execute".
     */
    private BigDecimal resolveRequestedQuoteAmount(StrategyDecision decision, BigDecimal entryPrice) {
        if (decision == null) {
            return BigDecimal.ZERO;
        }

        if (decision.getNotionalSize() != null && decision.getNotionalSize().compareTo(BigDecimal.ZERO) > 0) {
            return decision.getNotionalSize();
        }

        if (decision.getPositionSize() != null && decision.getPositionSize().compareTo(BigDecimal.ZERO) > 0) {
            // positionSize is base-asset qty (BTC). Convert to USDT quote
            // using the fill price so the backtest's cash-flow math is
            // unit-consistent.
            if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            return decision.getPositionSize().multiply(entryPrice).setScale(8, RoundingMode.HALF_UP);
        }

        return BigDecimal.ZERO;
    }

    private String resolveStrategyName(BacktestRun backtestRun, StrategyDecision decision) {
        if (decision != null && StringUtils.hasText(decision.getStrategyCode())) {
            return decision.getStrategyCode();
        }

        if (StringUtils.hasText(backtestRun.getStrategyCode())) {
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
                    new PlannedPosition(POSITION_ROLE_RUNNER, totalQty, totalQuoteAmount, totalEntryFee, null)
            );
            default -> List.of(
                    new PlannedPosition(EXIT_STRUCTURE_SINGLE, totalQty, totalQuoteAmount, totalEntryFee, decision.getTakeProfitPrice1())
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
                new PlannedPosition(POSITION_ROLE_RUNNER, runnerQty, runnerQuote, runnerFee, null)
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
                new PlannedPosition(POSITION_ROLE_RUNNER, runnerQty, runnerQuote, runnerFee, null)
        );
    }

    private String resolveTradeMode(StrategyDecision decision) {
        if (decision == null || !StringUtils.hasText(decision.getExitStructure())) {
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
        if (SIDE_SHORT.equalsIgnoreCase(side)) {
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