package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.BacktestTradePosition;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.BacktestTradePositionRepository;
import id.co.blackheart.repository.BacktestTradeRepository;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestTradeExecutorService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_PARTIALLY_CLOSED = "PARTIALLY_CLOSED";

    private final BacktestTradeRepository backtestTradeRepository;
    private final BacktestTradePositionRepository backtestTradePositionRepository;

    public enum TradeType {
        LONG, SHORT
    }

    @Transactional
    public void execute(
            BacktestRun backtestRun,
            BacktestState state,
            StrategyContext context,
            StrategyDecision decision
    ) {
        if (decision == null || DecisionType.HOLD.equals(decision.getDecisionType())) {
            return;
        }

        switch (decision.getDecisionType()) {
            case OPEN_LONG -> openTrade(backtestRun, state, context, decision, TradeType.LONG);
            case OPEN_SHORT -> openTrade(backtestRun, state, context, decision, TradeType.SHORT);
            case CLOSE_LONG -> closeTradeByDecision(backtestRun, state, context, decision, TradeType.LONG);
            case CLOSE_SHORT -> closeTradeByDecision(backtestRun, state, context, decision, TradeType.SHORT);
            case UPDATE_TRAILING_STOP -> updateTrailingStop(state, decision);
            default -> log.debug("Unhandled decisionType={}", decision.getDecisionType());
        }
    }

    @Transactional
    public void closeTradeFromListener(
            BacktestRun run,
            BacktestState state,
            StrategyContext context,
            String exitReason,
            BigDecimal exitPrice
    ) {
        BacktestTrade activeTrade = state.getActiveTrade();
        if (activeTrade == null || state.getActiveTradePositions().isEmpty()) {
            return;
        }

        LocalDateTime exitTime = context.getMarketData().getEndTime();

        for (BacktestTradePosition position : state.getActiveTradePositions()) {
            closeSinglePosition(run, position, exitPrice, exitReason, exitTime);
        }

        persistAndFinalizeTrade(state);
    }

    private void openTrade(
            BacktestRun run,
            BacktestState state,
            StrategyContext context,
            StrategyDecision decision,
            TradeType tradeType
    ) {
        if (state.getActiveTrade() != null) {
            return;
        }

        if (context.getMarketData() == null) {
            return;
        }

        if (decision.getPositionSize() == null || decision.getPositionSize().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        if (state.getCashBalance() == null || state.getCashBalance().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        BigDecimal requestedQuoteAmount = decision.getPositionSize().min(state.getCashBalance());

        PreTradeValidationResult validation = validateBeforeOpen(
                run,
                context.getMarketData(),
                decision,
                requestedQuoteAmount,
                tradeType
        );

        if (!validation.valid) {
            log.warn(
                    "Backtest {} rejected before execution | asset={} reason={}",
                    tradeType,
                    context.getAsset(),
                    validation.reason
            );
            return;
        }

        BigDecimal entryPrice = applyEntrySlippage(
                context.getMarketData().getClosePrice(),
                safe(run.getSlippagePct()),
                tradeType
        );

        BigDecimal totalQty = floorToStep(
                requestedQuoteAmount.divide(entryPrice, 12, RoundingMode.DOWN),
                resolveQtyStep(run)
        );

        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        BigDecimal totalEntryQuoteQty = totalQty.multiply(entryPrice);
        BigDecimal entryFee = totalEntryQuoteQty.multiply(safe(run.getFeePct()));

        SplitPlan splitPlan = buildSplitPlan(
                run,
                totalQty,
                entryPrice,
                decision,
                tradeType
        );

        if (isInvalidPlan(splitPlan)) {
            splitPlan = buildSinglePlan(run, totalQty, entryPrice, decision);
        }

        if (isInvalidPlan(splitPlan)) {
            return;
        }

        LocalDateTime entryTime = context.getMarketData().getEndTime();

        BacktestTrade trade = BacktestTrade.builder()
                .backtestRunId(run.getBacktestRunId())
                .userStrategyId(context.getUserStrategyId())
                .strategyName(context.getStrategyCode())
                .interval(context.getInterval())
                .exchange("BINANCE")
                .asset(context.getAsset())
                .side(tradeType.name())
                .status(STATUS_OPEN)
                .tradeMode(splitPlan.tradeMode)
                .avgEntryPrice(entryPrice)
                .avgExitPrice(null)
                .totalEntryQty(totalQty)
                .totalEntryQuoteQty(totalEntryQuoteQty)
                .totalRemainingQty(totalQty)
                .realizedPnlAmount(BigDecimal.ZERO)
                .realizedPnlPercent(BigDecimal.ZERO)
                .totalFeeAmount(entryFee)
                .totalFeeCurrency("USDT")
                .exitReason(null)
                .entryTrendRegime(decision.getEntryTrendRegime())
                .entryAdx(decision.getEntryAdx())
                .entryAtr(decision.getEntryAtr())
                .entryRsi(decision.getEntryRsi())
                .entryTime(entryTime)
                .exitTime(null)
                .build();

        trade = backtestTradeRepository.save(trade);

        List<BacktestTradePosition> positions = buildTradePositions(
                trade,
                splitPlan,
                entryPrice,
                entryFee,
                "USDT",
                entryTime
        );

        positions = backtestTradePositionRepository.saveAll(positions);

        state.setCashBalance(state.getCashBalance().subtract(totalEntryQuoteQty).subtract(entryFee));
        state.setAssetBalance(totalQty);
        state.setActiveTrade(trade);
        state.setActiveTradePositions(new ArrayList<>(positions));

        log.info(
                "✅ Backtest {} opened | tradeId={} asset={} entryPrice={} qty={} mode={}",
                tradeType,
                trade.getBacktestTradeId(),
                trade.getAsset(),
                entryPrice,
                totalQty,
                splitPlan.tradeMode
        );
    }

    private void closeTradeByDecision(
            BacktestRun run,
            BacktestState state,
            StrategyContext context,
            StrategyDecision decision,
            TradeType expectedTradeType
    ) {
        log.info("Trade Closed {}", decision.getReason());
        BacktestTrade activeTrade = state.getActiveTrade();
        if (activeTrade == null || state.getActiveTradePositions().isEmpty()) {
            return;
        }

        if (!expectedTradeType.name().equalsIgnoreCase(activeTrade.getSide())) {
            return;
        }

        BigDecimal exitPrice = applyExitSlippage(
                context.getMarketData().getClosePrice(),
                safe(run.getSlippagePct()),
                expectedTradeType
        );

        LocalDateTime exitTime = context.getMarketData().getEndTime();
        String exitReason = decision.getExitReason() == null ? "MANUAL_CLOSE" : decision.getExitReason();

        for (BacktestTradePosition position : state.getActiveTradePositions()) {
            closeSinglePosition(run, position, exitPrice, exitReason, exitTime);
        }

        persistAndFinalizeTrade(state);
    }

    private void updateTrailingStop(
            BacktestState state,
            StrategyDecision decision
    ) {
        BacktestTrade activeTrade = state.getActiveTrade();
        if (activeTrade == null || state.getActiveTradePositions().isEmpty()) {
            return;
        }

        for (BacktestTradePosition position : state.getActiveTradePositions()) {
            if (decision.getTrailingStopPrice() != null) {
                position.setTrailingStopPrice(decision.getTrailingStopPrice());
            }
            if (decision.getStopLossPrice() != null) {
                position.setCurrentStopLossPrice(decision.getStopLossPrice());
            }
        }

        backtestTradePositionRepository.saveAll(state.getActiveTradePositions());
    }

    private void persistAndFinalizeTrade(BacktestState state) {
        BacktestTrade trade = state.getActiveTrade();
        if (trade == null) {
            return;
        }

        List<BacktestTradePosition> positions = state.getActiveTradePositions();
        if (positions == null || positions.isEmpty()) {
            return;
        }

        backtestTradePositionRepository.saveAll(positions);

        recalculateParentTradeSummaryInMemory(trade, positions);
        trade = backtestTradeRepository.save(trade);

        if (STATUS_CLOSED.equalsIgnoreCase(trade.getStatus())) {
            state.getCompletedTrades().add(trade);
            state.getCompletedTradePositions().addAll(positions);

            state.setCashBalance(
                    safe(state.getCashBalance())
                            .add(safe(trade.getTotalEntryQuoteQty()))
                            .add(safe(trade.getRealizedPnlAmount()))
            );
            state.setAssetBalance(BigDecimal.ZERO);
            state.setActiveTrade(null);
            state.setActiveTradePositions(new ArrayList<>());
        } else {
            state.setActiveTrade(trade);
            state.setAssetBalance(safe(trade.getTotalRemainingQty()));
        }
    }

    private void recalculateParentTradeSummaryInMemory(
            BacktestTrade trade,
            List<BacktestTradePosition> allPositions
    ) {
        BigDecimal totalRemainingQty = allPositions.stream()
                .map(BacktestTradePosition::getRemainingQty)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal realizedPnlAmount = allPositions.stream()
                .map(BacktestTradePosition::getRealizedPnlAmount)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalFeeAmount = allPositions.stream()
                .map(tp -> safe(tp.getEntryFee()).add(safe(tp.getExitFee())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<BacktestTradePosition> closedPositions = allPositions.stream()
                .filter(tp -> STATUS_CLOSED.equalsIgnoreCase(tp.getStatus()))
                .toList();

        BigDecimal avgExitPrice = null;
        if (!closedPositions.isEmpty()) {
            BigDecimal totalClosedQty = closedPositions.stream()
                    .map(BacktestTradePosition::getExitExecutedQty)
                    .filter(v -> v != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalClosedQuote = closedPositions.stream()
                    .map(tp -> safe(tp.getExitPrice()).multiply(safe(tp.getExitExecutedQty())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (totalClosedQty.compareTo(BigDecimal.ZERO) > 0) {
                avgExitPrice = totalClosedQuote.divide(totalClosedQty, 8, RoundingMode.HALF_UP);
            }
        }

        long openCount = allPositions.stream()
                .filter(tp -> STATUS_OPEN.equalsIgnoreCase(tp.getStatus()))
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

            BacktestTradePosition latestClosed = closedPositions.stream()
                    .max(Comparator.comparing(
                            BacktestTradePosition::getExitTime,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);

            if (latestClosed != null) {
                trade.setExitTime(latestClosed.getExitTime());
                trade.setExitReason(latestClosed.getExitReason());
            }
        } else if (openCount < allPositions.size()) {
            trade.setStatus(STATUS_PARTIALLY_CLOSED);
        } else {
            trade.setStatus(STATUS_OPEN);
        }
    }

    private List<BacktestTradePosition> buildTradePositions(
            BacktestTrade trade,
            SplitPlan splitPlan,
            BigDecimal avgEntryPrice,
            BigDecimal totalEntryFee,
            String feeCurrency,
            LocalDateTime entryTime
    ) {
        BigDecimal totalQty = splitPlan.positions.stream()
                .map(p -> p.qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal allocatedFeeRunning = BigDecimal.ZERO;
        List<BacktestTradePosition> positions = new ArrayList<>();

        for (int i = 0; i < splitPlan.positions.size(); i++) {
            PlannedPosition plannedPosition = splitPlan.positions.get(i);

            BigDecimal entryFee;
            if (i == splitPlan.positions.size() - 1) {
                entryFee = safe(totalEntryFee).subtract(allocatedFeeRunning);
            } else {
                entryFee = safe(totalEntryFee)
                        .multiply(plannedPosition.qty)
                        .divide(totalQty, 12, RoundingMode.HALF_UP);
                allocatedFeeRunning = allocatedFeeRunning.add(entryFee);
            }

            BigDecimal entryQuoteQty = avgEntryPrice.multiply(plannedPosition.qty);

            BacktestTradePosition position = BacktestTradePosition.builder()
                    .tradeId(trade.getBacktestTradeId())
                    .backtestRunId(trade.getBacktestRunId())
                    .userStrategyId(trade.getUserStrategyId())
                    .asset(trade.getAsset())
                    .interval(trade.getInterval())
                    .exchange(trade.getExchange())
                    .side(trade.getSide())
                    .positionRole(plannedPosition.role)
                    .status(STATUS_OPEN)
                    .entryPrice(avgEntryPrice)
                    .entryQty(plannedPosition.qty)
                    .entryQuoteQty(entryQuoteQty)
                    .remainingQty(plannedPosition.qty)
                    .exitPrice(null)
                    .exitExecutedQty(null)
                    .exitExecutedQuoteQty(null)
                    .entryFee(entryFee)
                    .entryFeeCurrency(feeCurrency)
                    .exitFee(null)
                    .exitFeeCurrency(null)
                    .initialStopLossPrice(plannedPosition.initialStopLoss)
                    .currentStopLossPrice(plannedPosition.currentStopLoss)
                    .trailingStopPrice(plannedPosition.trailingStop)
                    .takeProfitPrice(plannedPosition.takeProfit)
                    .highestPriceSinceEntry("LONG".equalsIgnoreCase(trade.getSide()) ? avgEntryPrice : null)
                    .lowestPriceSinceEntry("SHORT".equalsIgnoreCase(trade.getSide()) ? avgEntryPrice : null)
                    .realizedPnlAmount(BigDecimal.ZERO)
                    .realizedPnlPercent(BigDecimal.ZERO)
                    .exitReason(null)
                    .entryTime(entryTime)
                    .exitTime(null)
                    .build();

            positions.add(position);
        }

        return positions;
    }

    private PreTradeValidationResult validateBeforeOpen(
            BacktestRun run,
            MarketData marketData,
            StrategyDecision decision,
            BigDecimal requestedQuoteAmount,
            TradeType tradeType
    ) {
        if (marketData == null) {
            return PreTradeValidationResult.invalid("Market data is null");
        }

        if (requestedQuoteAmount == null || requestedQuoteAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return PreTradeValidationResult.invalid("Requested quote amount must be greater than zero");
        }

        BigDecimal price = applyEntrySlippage(
                marketData.getClosePrice(),
                safe(run.getSlippagePct()),
                tradeType
        );

        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return PreTradeValidationResult.invalid("Entry price is invalid");
        }

        BigDecimal qtyStep = resolveQtyStep(run);
        BigDecimal minQty = resolveMinQty(run);
        BigDecimal minNotional = resolveMinNotional(run);

        BigDecimal estimatedQty = floorToStep(
                requestedQuoteAmount.divide(price, 12, RoundingMode.DOWN),
                qtyStep
        );

        if (estimatedQty.compareTo(minQty) < 0) {
            return PreTradeValidationResult.invalid("Estimated quantity below minimum quantity");
        }

        BigDecimal estimatedNotional = estimatedQty.multiply(price);
        if (estimatedNotional.compareTo(minNotional) < 0) {
            return PreTradeValidationResult.invalid("Estimated notional below minimum notional");
        }

        SplitPlan estimatedPlan = buildSplitPlan(run, estimatedQty, price, decision, tradeType);
        if (isInvalidPlan(estimatedPlan)) {
            SplitPlan singlePlan = buildSinglePlan(run, estimatedQty, price, decision);
            if (isInvalidPlan(singlePlan)) {
                return PreTradeValidationResult.invalid("No valid split plan can be generated");
            }

            return PreTradeValidationResult.validation(estimatedQty, price, singlePlan.tradeMode);
        }

        return PreTradeValidationResult.validation(estimatedQty, price, estimatedPlan.tradeMode);
    }

    private void closeSinglePosition(
            BacktestRun run,
            BacktestTradePosition position,
            BigDecimal exitPrice,
            String exitReason,
            LocalDateTime exitTime
    ) {
        if (position == null || !STATUS_OPEN.equalsIgnoreCase(position.getStatus())) {
            return;
        }

        BigDecimal exitQty = safe(position.getRemainingQty());
        BigDecimal exitQuoteQty = exitQty.multiply(exitPrice);
        BigDecimal exitFee = exitQuoteQty.multiply(safe(run.getFeePct()));

        TradeType tradeType = TradeType.valueOf(position.getSide());

        BigDecimal pnlAmount = calculatePLAmount(
                safe(position.getEntryPrice()),
                exitPrice,
                safe(position.getEntryQty()),
                tradeType
        ).subtract(safe(position.getEntryFee())).subtract(exitFee);

        BigDecimal pnlPercent = BigDecimal.ZERO;
        if (safe(position.getEntryQuoteQty()).compareTo(BigDecimal.ZERO) > 0) {
            pnlPercent = pnlAmount
                    .divide(position.getEntryQuoteQty(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        position.setExitPrice(exitPrice);
        position.setExitExecutedQty(exitQty);
        position.setExitExecutedQuoteQty(exitQuoteQty);
        position.setExitFee(exitFee);
        position.setExitFeeCurrency("USDT");
        position.setRemainingQty(BigDecimal.ZERO);
        position.setStatus(STATUS_CLOSED);
        position.setRealizedPnlAmount(pnlAmount);
        position.setRealizedPnlPercent(pnlPercent);
        position.setExitReason(exitReason);
        position.setExitTime(exitTime);
    }

    private SplitPlan buildSplitPlan(
            BacktestRun run,
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision,
            TradeType tradeType
    ) {
        SplitPlan threeSlicePlan = tryThreeSlicePlan(run, totalQty, avgEntryPrice, decision, tradeType);
        if (!isInvalidPlan(threeSlicePlan)) {
            return threeSlicePlan;
        }

        SplitPlan twoSlicePlan = tryTwoSlicePlan(run, totalQty, avgEntryPrice, decision);
        if (!isInvalidPlan(twoSlicePlan)) {
            return twoSlicePlan;
        }

        return buildSinglePlan(run, totalQty, avgEntryPrice, decision);
    }

    private SplitPlan buildSinglePlan(
            BacktestRun run,
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        return trySinglePlan(run, totalQty, avgEntryPrice, decision);
    }

    private SplitPlan tryThreeSlicePlan(
            BacktestRun run,
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision,
            TradeType tradeType
    ) {
        BigDecimal qtyStep = resolveQtyStep(run);

        BigDecimal tp1Qty = floorToStep(totalQty.multiply(new BigDecimal("0.30")), qtyStep);
        BigDecimal tp2Qty = floorToStep(totalQty.multiply(new BigDecimal("0.30")), qtyStep);
        BigDecimal runnerQty = floorToStep(totalQty.subtract(tp1Qty).subtract(tp2Qty), qtyStep);

        if (!isClosableSlice(run, tp1Qty, avgEntryPrice)
                || !isClosableSlice(run, tp2Qty, avgEntryPrice)
                || !isClosableSlice(run, runnerQty, avgEntryPrice)) {
            return invalidPlan();
        }

        BigDecimal baseTp = decision.getTakeProfitPrice();
        BigDecimal stop = decision.getStopLossPrice();
        BigDecimal tp2 = deriveSecondTakeProfit(avgEntryPrice, baseTp, tradeType);

        return new SplitPlan(
                "TP1_TP2_RUNNER",
                List.of(
                        PlannedPosition.of("TP1", tp1Qty, stop, stop, decision.getTrailingStopPrice(), baseTp),
                        PlannedPosition.of("TP2", tp2Qty, stop, stop, decision.getTrailingStopPrice(), tp2),
                        PlannedPosition.of("RUNNER", runnerQty, stop, stop, decision.getTrailingStopPrice(), null)
                )
        );
    }

    private SplitPlan tryTwoSlicePlan(
            BacktestRun run,
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        BigDecimal qtyStep = resolveQtyStep(run);

        BigDecimal tp1Qty = floorToStep(totalQty.multiply(new BigDecimal("0.50")), qtyStep);
        BigDecimal runnerQty = floorToStep(totalQty.subtract(tp1Qty), qtyStep);

        if (!isClosableSlice(run, tp1Qty, avgEntryPrice)
                || !isClosableSlice(run, runnerQty, avgEntryPrice)) {
            return invalidPlan();
        }

        return new SplitPlan(
                "TP1_RUNNER",
                List.of(
                        PlannedPosition.of("TP1", tp1Qty, decision.getStopLossPrice(), decision.getStopLossPrice(), decision.getTrailingStopPrice(), decision.getTakeProfitPrice()),
                        PlannedPosition.of("RUNNER", runnerQty, decision.getStopLossPrice(), decision.getStopLossPrice(), decision.getTrailingStopPrice(), null)
                )
        );
    }

    private SplitPlan trySinglePlan(
            BacktestRun run,
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        BigDecimal singleQty = floorToStep(totalQty, resolveQtyStep(run));

        if (!isClosableSlice(run, singleQty, avgEntryPrice)) {
            return invalidPlan();
        }

        return new SplitPlan(
                "SINGLE",
                List.of(
                        PlannedPosition.of("SINGLE", singleQty, decision.getStopLossPrice(), decision.getStopLossPrice(), decision.getTrailingStopPrice(), decision.getTakeProfitPrice())
                )
        );
    }

    private boolean isClosableSlice(BacktestRun run, BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) {
            return false;
        }

        if (qty.compareTo(resolveMinQty(run)) < 0) {
            return false;
        }

        BigDecimal notional = qty.multiply(price);
        return notional.compareTo(resolveMinNotional(run)) >= 0;
    }

    private BigDecimal resolveMinQty(BacktestRun run) {
        return safe(run.getMinQty()).compareTo(BigDecimal.ZERO) > 0
                ? run.getMinQty()
                : new BigDecimal("0.000001");
    }

    private BigDecimal resolveMinNotional(BacktestRun run) {
        return safe(run.getMinNotional()).compareTo(BigDecimal.ZERO) > 0
                ? run.getMinNotional()
                : new BigDecimal("7");
    }

    private BigDecimal resolveQtyStep(BacktestRun run) {
        return safe(run.getQtyStep()).compareTo(BigDecimal.ZERO) > 0
                ? run.getQtyStep()
                : new BigDecimal("0.000001");
    }

    private BigDecimal applyEntrySlippage(
            BigDecimal rawPrice,
            BigDecimal slippagePct,
            TradeType tradeType
    ) {
        if (rawPrice == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal multiplier = tradeType == TradeType.LONG
                ? BigDecimal.ONE.add(slippagePct)
                : BigDecimal.ONE.subtract(slippagePct);

        return rawPrice.multiply(multiplier).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal applyExitSlippage(
            BigDecimal rawPrice,
            BigDecimal slippagePct,
            TradeType tradeType
    ) {
        if (rawPrice == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal multiplier = tradeType == TradeType.LONG
                ? BigDecimal.ONE.subtract(slippagePct)
                : BigDecimal.ONE.add(slippagePct);

        return rawPrice.multiply(multiplier).setScale(8, RoundingMode.HALF_UP);
    }

    private boolean isInvalidPlan(SplitPlan splitPlan) {
        return splitPlan == null || splitPlan.positions == null || splitPlan.positions.isEmpty();
    }

    private SplitPlan invalidPlan() {
        return new SplitPlan("INVALID", List.of());
    }

    private BigDecimal deriveSecondTakeProfit(
            BigDecimal entryPrice,
            BigDecimal baseTakeProfit,
            TradeType tradeType
    ) {
        if (entryPrice == null || baseTakeProfit == null) {
            return null;
        }

        BigDecimal distance = baseTakeProfit.subtract(entryPrice).abs();
        BigDecimal extendedDistance = distance.multiply(new BigDecimal("1.50"));

        if (tradeType == TradeType.LONG) {
            return entryPrice.add(extendedDistance);
        }

        return entryPrice.subtract(extendedDistance);
    }

    private BigDecimal calculatePLAmount(
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal qty,
            TradeType tradeType
    ) {
        if (entryPrice == null || exitPrice == null || qty == null) {
            return BigDecimal.ZERO;
        }

        return switch (tradeType) {
            case LONG -> exitPrice.subtract(entryPrice).multiply(qty);
            case SHORT -> entryPrice.subtract(exitPrice).multiply(qty);
        };
    }

    private BigDecimal floorToStep(BigDecimal qty, BigDecimal step) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.DOWN);
        }

        BigDecimal steps = qty.divide(step, 0, RoundingMode.DOWN);
        return steps.multiply(step).setScale(8, RoundingMode.DOWN);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static class SplitPlan {
        final String tradeMode;
        final List<PlannedPosition> positions;

        SplitPlan(String tradeMode, List<PlannedPosition> positions) {
            this.tradeMode = tradeMode;
            this.positions = positions;
        }
    }

    private static class PlannedPosition {
        final String role;
        final BigDecimal qty;
        final BigDecimal initialStopLoss;
        final BigDecimal currentStopLoss;
        final BigDecimal trailingStop;
        final BigDecimal takeProfit;

        private PlannedPosition(
                String role,
                BigDecimal qty,
                BigDecimal initialStopLoss,
                BigDecimal currentStopLoss,
                BigDecimal trailingStop,
                BigDecimal takeProfit
        ) {
            this.role = role;
            this.qty = qty;
            this.initialStopLoss = initialStopLoss;
            this.currentStopLoss = currentStopLoss;
            this.trailingStop = trailingStop;
            this.takeProfit = takeProfit;
        }

        static PlannedPosition of(
                String role,
                BigDecimal qty,
                BigDecimal initialStopLoss,
                BigDecimal currentStopLoss,
                BigDecimal trailingStop,
                BigDecimal takeProfit
        ) {
            return new PlannedPosition(role, qty, initialStopLoss, currentStopLoss, trailingStop, takeProfit);
        }
    }

    private static class PreTradeValidationResult {
        final boolean valid;
        final String reason;
        final BigDecimal estimatedQty;
        final BigDecimal estimatedPrice;
        final String plannedMode;

        private PreTradeValidationResult(
                boolean valid,
                String reason,
                BigDecimal estimatedQty,
                BigDecimal estimatedPrice,
                String plannedMode
        ) {
            this.valid = valid;
            this.reason = reason;
            this.estimatedQty = estimatedQty;
            this.estimatedPrice = estimatedPrice;
            this.plannedMode = plannedMode;
        }

        static PreTradeValidationResult validation(
                BigDecimal estimatedQty,
                BigDecimal estimatedPrice,
                String plannedMode
        ) {
            return new PreTradeValidationResult(true, null, estimatedQty, estimatedPrice, plannedMode);
        }

        static PreTradeValidationResult invalid(String reason) {
            return new PreTradeValidationResult(false, reason, null, null, null);
        }
    }
}