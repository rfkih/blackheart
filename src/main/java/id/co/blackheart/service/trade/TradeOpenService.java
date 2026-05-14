package id.co.blackheart.service.trade;

import id.co.blackheart.dto.request.BinanceOrderRequest;
import id.co.blackheart.dto.response.BinanceOrderFill;
import id.co.blackheart.dto.response.BinanceOrderResponse;
import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.repository.TradesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static id.co.blackheart.util.TradeConstant.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeOpenService {

    private static final String POSITION_ROLE_RUNNER = "RUNNER";

    private final TradesRepository tradesRepository;
    private final TradePositionRepository tradePositionRepository;
    private final TradeExecutionService tradeExecutionService;
    private final TradeStateSyncService tradeStateSyncService;
    private final TradeExecutionLogService tradeExecutionLogService;

    public void openMarketOrder(
            EnrichedStrategyContext context,
            StrategyDecision decision,
            BigDecimal tradeAmount,
            TradeType tradeType,
            String asset
    ) {
        UUID tradeId = openParentTrade(context, decision, tradeAmount, tradeType, asset);

        if (tradeId == null) {
            log.info("Failed to create parent trade | type={} asset={}", tradeType, asset);
            return;
        }

        tradeStateSyncService.syncTradeState(tradeId);
    }

    private UUID openParentTrade(
            EnrichedStrategyContext context,
            StrategyDecision decision,
            BigDecimal tradeQuoteNotional,
            TradeType tradeType,
            String asset
    ) {
        Trades persistedTrade = null;
        String strategyName = resolveStrategyName(context, decision);
        String entryReason = decision != null ? decision.getReason() : null;

        try {
            PreTradeValidationResult validation = validateBeforeOpen(
                    context,
                    decision,
                    tradeQuoteNotional,
                    tradeType,
                    asset
            );

            if (!validation.valid) {
                log.warn(
                        "🚫 {} trade rejected before execution | asset={} quoteNotional={} reason={}",
                        tradeType,
                        asset,
                        tradeQuoteNotional,
                        validation.reason
                );
                // V66 — pre-trade validation rejections (insufficient qty,
                // bad price, exit-plan invalid, balance check, …) used to
                // exit silently here; only post-Binance failures landed in
                // trade_execution_log. The "rejected_before_execution"
                // rows give operators a single auditable surface for every
                // attempted entry, success or fail.
                tradeExecutionLogService.logOpenFailure(
                        context != null ? context.getAccount() : null,
                        asset, strategyName, tradeType.name(),
                        entryReason, null,
                        "Pre-trade validation: " + validation.reason
                );
                return null;
            }

            String orderSide = tradeType == TradeType.LONG ? "BUY" : "SELL";
            BigDecimal requestAmount = tradeType == TradeType.LONG
                    ? tradeQuoteNotional
                    : validation.normalizedQty;

            BinanceOrderRequest request = BinanceOrderRequest.builder()
                    .symbol(asset)
                    .side(orderSide)
                    .amount(requestAmount)
                    .apiKey(context.getAccount().getApiKey())
                    .apiSecret(context.getAccount().getApiSecret())
                    .build();

            BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(request);
            FillProcessingResult result = processOrderFills(response);

            BigDecimal avgEntryPrice = result.avgPrice;
            BigDecimal normalizedExecutedQty = floorToStep(result.totalQty);
            LocalDateTime now = LocalDateTime.now();

            if (normalizedExecutedQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Executed quantity became zero after normalization");
            }

            persistedTrade = Trades.builder()
                    .accountId(context.getAccount().getAccountId())
                    .accountStrategyId(context.getAccountStrategy().getAccountStrategyId())
                    .strategyName(resolveStrategyName(context, decision))
                    .interval(context.getInterval())
                    .exchange("BINANCE")
                    .asset(asset)
                    .side(tradeType.name())
                    .status(STATUS_OPEN)
                    .tradeMode("PENDING_ALLOCATION")
                    .avgEntryPrice(avgEntryPrice)
                    .avgExitPrice(null)
                    .totalEntryQty(normalizedExecutedQty)
                    .totalEntryQuoteQty(result.totalQuote)
                    .totalRemainingQty(normalizedExecutedQty)
                    .realizedPnlAmount(BigDecimal.ZERO)
                    .realizedPnlPercent(BigDecimal.ZERO)
                    .totalFeeAmount(result.totalFee)
                    .totalFeeCurrency(result.feeCurrency)
                    .exitReason(null)
                    .entryTrendRegime(decision.getEntryTrendRegime())
                    .entryAdx(decision.getEntryAdx())
                    .entryAtr(decision.getEntryAtr())
                    .entryRsi(decision.getEntryRsi())
                    .entryTime(now)
                    .exitTime(null)
                    // Phase 2c — decision intent for P&L attribution at close.
                    .intendedEntryPrice(decision.getIntendedEntryPrice())
                    .intendedSize(decision.getIntendedSize())
                    .decisionTime(decision.getDecisionTime())
                    .build();

            tradesRepository.save(persistedTrade);

            SplitPlan finalPlan = buildSplitPlan(normalizedExecutedQty, avgEntryPrice, decision);

            if (isInvalidPlan(finalPlan)) {
                persistedTrade.setTradeMode("UNALLOCATED");
                persistedTrade.setExitReason("EXIT_PLAN_PENDING");
                tradesRepository.save(persistedTrade);
                return persistedTrade.getTradeId();
            }

            persistedTrade.setTradeMode(finalPlan.tradeMode);
            tradesRepository.save(persistedTrade);

            allocateAndPersistTradePositions(
                    persistedTrade,
                    finalPlan,
                    avgEntryPrice,
                    result.totalFee,
                    result.feeCurrency,
                    now
            );

            tradeExecutionLogService.logOpenSuccess(
                    context.getAccount(), asset, strategyName, tradeType.name(),
                    entryReason, persistedTrade.getTradeId()
            );

            return persistedTrade.getTradeId();

        } catch (Exception e) {
            log.error("❌ Error placing {} parent trade for {}", tradeType, asset, e);

            UUID failedTradeId = persistedTrade != null ? persistedTrade.getTradeId() : null;
            tradeExecutionLogService.logOpenFailure(
                    context != null ? context.getAccount() : null,
                    asset, strategyName, tradeType.name(), entryReason, failedTradeId, e.getMessage()
            );

            if (persistedTrade != null) {
                persistedTrade.setTradeMode("UNALLOCATED");
                persistedTrade.setExitReason("EXIT_PLAN_PENDING");
                tradesRepository.save(persistedTrade);
                return persistedTrade.getTradeId();
            }

            return null;
        }
    }

    private PreTradeValidationResult validateBeforeOpen(
            EnrichedStrategyContext context,
            StrategyDecision decision,
            BigDecimal tradeAmount,
            TradeType tradeType,
            String asset
    ) {
        if (context == null) {
            return PreTradeValidationResult.invalid("EnrichedStrategyContext is null");
        }

        if (context.getAccount() == null) {
            return PreTradeValidationResult.invalid("Account is null");
        }

        if (context.getAccountStrategy() == null) {
            return PreTradeValidationResult.invalid("AccountStrategy is null");
        }

        if (!StringUtils.hasText(asset)) {
            return PreTradeValidationResult.invalid("Asset is null or blank");
        }

        if (decision == null) {
            return PreTradeValidationResult.invalid("StrategyDecision is null");
        }

        if (tradeAmount == null || tradeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return PreTradeValidationResult.invalid("Trade amount must be greater than zero");
        }

        BigDecimal referencePrice = resolveReferencePrice(decision, tradeType);
        if (referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return PreTradeValidationResult.invalid("Unable to resolve reference price for pre-validation");
        }

        BigDecimal bufferedPrice = applyValidationBuffer(referencePrice, tradeType);

        BigDecimal estimatedQty = tradeType == TradeType.SHORT
                ? tradeAmount
                : tradeAmount.divide(bufferedPrice, 12, RoundingMode.DOWN);

        BigDecimal normalizedQty = floorToStep(estimatedQty);

        log.info(
                "Pre-trade validation | type={} asset={} tradeAmount={} bufferedPrice={} estimatedQty={} normalizedQty={} step={}",
                tradeType,
                asset,
                tradeAmount,
                bufferedPrice,
                estimatedQty,
                normalizedQty,
                DEFAULT_QTY_STEP
        );

        if (normalizedQty.compareTo(BigDecimal.ZERO) <= 0) {
            return PreTradeValidationResult.invalid("Estimated quantity is zero after step normalization");
        }

        if (!isStepValid(normalizedQty)) {
            return PreTradeValidationResult.invalid("Estimated quantity is invalid for step size");
        }

        if (normalizedQty.compareTo(MIN_POSITION_QTY) < 0) {
            return PreTradeValidationResult.invalid("Estimated quantity below minimum position quantity");
        }

        BigDecimal estimatedNotional = normalizedQty.multiply(bufferedPrice);
        if (estimatedNotional.compareTo(MIN_USDT_NOTIONAL) < 0) {
            return PreTradeValidationResult.invalid(
                    String.format(
                            "Estimated notional below minimum notional. min=%s, estimated=%s",
                            MIN_USDT_NOTIONAL,
                            estimatedNotional
                    )
            );
        }

        SplitPlan estimatedPlan = buildSplitPlan(normalizedQty, bufferedPrice, decision);
        if (isInvalidPlan(estimatedPlan)) {
            return PreTradeValidationResult.invalid("No valid exit structure can be generated for estimated quantity");
        }

        return PreTradeValidationResult.accepted(
                estimatedQty,
                normalizedQty,
                bufferedPrice,
                estimatedPlan.tradeMode
        );
    }

    private String resolveStrategyName(EnrichedStrategyContext context, StrategyDecision decision) {
        if (decision != null && StringUtils.hasText(decision.getStrategyCode())) {
            return decision.getStrategyCode();
        }

        if (context != null
                && context.getAccountStrategy() != null
                && StringUtils.hasText(context.getAccountStrategy().getStrategyCode())) {
            return context.getAccountStrategy().getStrategyCode();
        }

        return "UNKNOWN_STRATEGY";
    }

    private BigDecimal resolveReferencePrice(StrategyDecision decision, TradeType tradeType) {
        BigDecimal tp1 = decision.getTakeProfitPrice1();
        BigDecimal tp2 = decision.getTakeProfitPrice2();
        BigDecimal stop = decision.getStopLossPrice();

        if (tp1 != null && stop != null) {
            boolean isValidMidpoint =
                    (tradeType == TradeType.LONG && tp1.compareTo(stop) > 0)
                            || (tradeType == TradeType.SHORT && stop.compareTo(tp1) > 0);

            if (isValidMidpoint) {
                return tp1.add(stop).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
            }
        }

        if (tp1 != null && tp1.compareTo(BigDecimal.ZERO) > 0) {
            return tp1;
        }
        if (tp2 != null && tp2.compareTo(BigDecimal.ZERO) > 0) {
            return tp2;
        }
        if (stop != null && stop.compareTo(BigDecimal.ZERO) > 0) {
            return stop;
        }

        return null;
    }

    private BigDecimal applyValidationBuffer(BigDecimal referencePrice, TradeType tradeType) {
        return tradeType == TradeType.LONG
                ? referencePrice.multiply(BUY_PRICE_BUFFER)
                : referencePrice.multiply(SELL_PRICE_BUFFER);
    }

    private FillProcessingResult processOrderFills(BinanceOrderResponse response) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalQuote = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        String feeCurrency = null;

        if (!CollectionUtils.isEmpty(response.getFills())) {
            for (BinanceOrderFill fill : response.getFills()) {
                BigDecimal qty = new BigDecimal(fill.getQty());
                BigDecimal price = new BigDecimal(fill.getPrice());
                BigDecimal commission = new BigDecimal(fill.getCommission());

                totalQty = totalQty.add(qty);
                totalQuote = totalQuote.add(price.multiply(qty));
                totalFee = totalFee.add(commission);

                if (feeCurrency == null && fill.getCommissionAsset() != null) {
                    feeCurrency = fill.getCommissionAsset();
                }
            }
        }

        if (totalQty.compareTo(BigDecimal.ZERO) <= 0 && response.getExecutedQty() != null) {
            totalQty = new BigDecimal(response.getExecutedQty());
        }

        if (totalQuote.compareTo(BigDecimal.ZERO) <= 0 && response.getCummulativeQuoteQty() != null) {
            totalQuote = new BigDecimal(response.getCummulativeQuoteQty());
        }

        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Executed quantity is zero");
        }

        if (totalQuote.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Executed quote quantity is zero");
        }

        BigDecimal avgPrice = totalQuote.divide(totalQty, 8, RoundingMode.HALF_UP);
        return new FillProcessingResult(totalQty, totalQuote, totalFee, feeCurrency, avgPrice);
    }

    private void allocateAndPersistTradePositions(
            Trades trade,
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

        for (int i = 0; i < splitPlan.positions.size(); i++) {
            PlannedPosition plannedPosition = splitPlan.positions.get(i);

            if (!isStepValid(plannedPosition.qty)) {
                throw new IllegalStateException(
                        "Planned position qty is not aligned to step size. role="
                                + plannedPosition.role + ", qty=" + plannedPosition.qty
                );
            }

            if (plannedPosition.qty.compareTo(MIN_POSITION_QTY) < 0) {
                throw new IllegalStateException(
                        "Planned position qty below minimum allowed qty. role="
                                + plannedPosition.role + ", qty=" + plannedPosition.qty
                );
            }

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

            TradePosition tradePosition = TradePosition.builder()
                    .tradePositionId(UUID.randomUUID())
                    .tradeId(trade.getTradeId())
                    .accountId(trade.getAccountId())
                    .accountStrategyId(trade.getAccountStrategyId())
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

            tradePositionRepository.save(tradePosition);
        }
    }

    private String normalizeExitStructure(String exitStructure) {
        if (!StringUtils.hasText(exitStructure)) {
            return EXIT_STRUCTURE_SINGLE;
        }
        return exitStructure.trim().toUpperCase();
    }

    private SplitPlan buildSplitPlan(
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        String exitStructure = normalizeExitStructure(decision.getExitStructure());

        return switch (exitStructure) {
            case EXIT_STRUCTURE_TP1_TP2_RUNNER -> buildPreferredThreeSlicePlan(totalQty, avgEntryPrice, decision);
            case EXIT_STRUCTURE_TP1_RUNNER -> buildPreferredTwoSlicePlan(totalQty, avgEntryPrice, decision);
            case EXIT_STRUCTURE_RUNNER_ONLY -> buildPreferredRunnerOnlyPlan(totalQty, avgEntryPrice, decision);
            case EXIT_STRUCTURE_SINGLE -> buildSinglePlan(totalQty, avgEntryPrice, decision);
            default -> buildSinglePlan(totalQty, avgEntryPrice, decision);
        };
    }

    private SplitPlan buildPreferredTwoSlicePlan(
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        SplitPlan twoSlicePlan = tryTwoSlicePlan(totalQty, avgEntryPrice, decision);
        if (!isInvalidPlan(twoSlicePlan)) {
            return twoSlicePlan;
        }

        log.warn("Downgrading exit structure from TP1_RUNNER to SINGLE");
        return buildSinglePlan(totalQty, avgEntryPrice, decision);
    }

    private SplitPlan buildPreferredRunnerOnlyPlan(
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        SplitPlan runnerOnlyPlan = tryRunnerOnlyPlan(totalQty, avgEntryPrice, decision);
        if (!isInvalidPlan(runnerOnlyPlan)) {
            return runnerOnlyPlan;
        }

        log.warn("Downgrading exit structure from RUNNER_ONLY to SINGLE");
        return buildSinglePlan(totalQty, avgEntryPrice, decision);
    }

    private SplitPlan buildPreferredThreeSlicePlan(
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        SplitPlan threeSlicePlan = tryThreeSlicePlan(totalQty, avgEntryPrice, decision);
        if (!isInvalidPlan(threeSlicePlan)) {
            return threeSlicePlan;
        }

        SplitPlan twoSlicePlan = tryTwoSlicePlan(totalQty, avgEntryPrice, decision);
        if (!isInvalidPlan(twoSlicePlan)) {
            log.warn("Downgrading exit structure from TP1_TP2_RUNNER to TP1_RUNNER");
            return twoSlicePlan;
        }

        log.warn("Downgrading exit structure from TP1_TP2_RUNNER to SINGLE");
        return buildSinglePlan(totalQty, avgEntryPrice, decision);
    }

    private SplitPlan buildSinglePlan(
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        return trySinglePlan(totalQty, avgEntryPrice, decision);
    }

    private SplitPlan tryThreeSlicePlan(
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        if (decision.getTakeProfitPrice1() == null || decision.getTakeProfitPrice2() == null) {
            return invalidPlan();
        }

        BigDecimal normalizedTotalQty = floorToStep(totalQty);
        if (normalizedTotalQty.compareTo(BigDecimal.ZERO) <= 0) {
            return invalidPlan();
        }

        BigDecimal tp1Qty = floorToStep(normalizedTotalQty.multiply(new BigDecimal("0.30")));
        BigDecimal tp2Qty = floorToStep(normalizedTotalQty.multiply(new BigDecimal("0.30")));
        BigDecimal runnerQty = normalizedTotalQty.subtract(tp1Qty).subtract(tp2Qty);

        BigDecimal allocatedTotal = tp1Qty.add(tp2Qty).add(runnerQty);
        if (allocatedTotal.compareTo(normalizedTotalQty) != 0) {
            log.error("Three-slice allocation mismatch | totalQty={} allocated={}", normalizedTotalQty, allocatedTotal);
            return invalidPlan();
        }

        if (isInvalidSlice(tp1Qty, avgEntryPrice)
                || isInvalidSlice(tp2Qty, avgEntryPrice)
                || isInvalidSlice(runnerQty, avgEntryPrice)) {
            return invalidPlan();
        }

        return new SplitPlan(
                EXIT_STRUCTURE_TP1_TP2_RUNNER,
                List.of(
                        PlannedPosition.of(
                                "TP1",
                                tp1Qty,
                                decision.getStopLossPrice(),
                                decision.getStopLossPrice(),
                                decision.getTrailingStopPrice(),
                                decision.getTakeProfitPrice1()
                        ),
                        PlannedPosition.of(
                                "TP2",
                                tp2Qty,
                                decision.getStopLossPrice(),
                                decision.getStopLossPrice(),
                                decision.getTrailingStopPrice(),
                                decision.getTakeProfitPrice2()
                        ),
                        PlannedPosition.of(
                                POSITION_ROLE_RUNNER,
                                runnerQty,
                                decision.getStopLossPrice(),
                                decision.getStopLossPrice(),
                                decision.getTrailingStopPrice(),
                                null
                        )
                )
        );
    }

    private SplitPlan tryTwoSlicePlan(
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        if (decision.getTakeProfitPrice1() == null) {
            return invalidPlan();
        }

        BigDecimal normalizedTotalQty = floorToStep(totalQty);
        if (normalizedTotalQty.compareTo(BigDecimal.ZERO) <= 0) {
            return invalidPlan();
        }

        BigDecimal tp1Qty = floorToStep(normalizedTotalQty.multiply(new BigDecimal("0.50")));
        BigDecimal runnerQty = normalizedTotalQty.subtract(tp1Qty);

        BigDecimal allocatedTotal = tp1Qty.add(runnerQty);
        if (allocatedTotal.compareTo(normalizedTotalQty) != 0) {
            log.error("Two-slice allocation mismatch | totalQty={} allocated={}", normalizedTotalQty, allocatedTotal);
            return invalidPlan();
        }

        if (isInvalidSlice(tp1Qty, avgEntryPrice) || isInvalidSlice(runnerQty, avgEntryPrice)) {
            return invalidPlan();
        }

        return new SplitPlan(
                EXIT_STRUCTURE_TP1_RUNNER,
                List.of(
                        PlannedPosition.of(
                                "TP1",
                                tp1Qty,
                                decision.getStopLossPrice(),
                                decision.getStopLossPrice(),
                                decision.getTrailingStopPrice(),
                                decision.getTakeProfitPrice1()
                        ),
                        PlannedPosition.of(
                                POSITION_ROLE_RUNNER,
                                runnerQty,
                                decision.getStopLossPrice(),
                                decision.getStopLossPrice(),
                                decision.getTrailingStopPrice(),
                                null
                        )
                )
        );
    }

    private SplitPlan tryRunnerOnlyPlan(
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        BigDecimal runnerQty = floorToStep(totalQty);

        if (isInvalidSlice(runnerQty, avgEntryPrice)) {
            return invalidPlan();
        }

        return new SplitPlan(
                EXIT_STRUCTURE_RUNNER_ONLY,
                List.of(
                        PlannedPosition.of(
                                POSITION_ROLE_RUNNER,
                                runnerQty,
                                decision.getStopLossPrice(),
                                decision.getStopLossPrice(),
                                decision.getTrailingStopPrice(),
                                null
                        )
                )
        );
    }

    private SplitPlan trySinglePlan(
            BigDecimal totalQty,
            BigDecimal avgEntryPrice,
            StrategyDecision decision
    ) {
        BigDecimal singleQty = floorToStep(totalQty);

        if (isInvalidSlice(singleQty, avgEntryPrice)) {
            return invalidPlan();
        }

        BigDecimal singleTp = decision.getTakeProfitPrice1();

        return new SplitPlan(
                EXIT_STRUCTURE_SINGLE,
                List.of(
                        PlannedPosition.of(
                                "SINGLE",
                                singleQty,
                                decision.getStopLossPrice(),
                                decision.getStopLossPrice(),
                                decision.getTrailingStopPrice(),
                                singleTp
                        )
                )
        );
    }

    private boolean isInvalidSlice(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) {
            return true;
        }

        if (!isStepValid(qty)) {
            return true;
        }

        if (qty.compareTo(MIN_POSITION_QTY) < 0) {
            return true;
        }

        BigDecimal notional = qty.multiply(price);
        return notional.compareTo(MIN_USDT_NOTIONAL) < 0;
    }

    private boolean isInvalidPlan(SplitPlan splitPlan) {
        return splitPlan == null || CollectionUtils.isEmpty(splitPlan.positions);
    }

    private SplitPlan invalidPlan() {
        return new SplitPlan("INVALID", List.of());
    }

    private BigDecimal floorToStep(BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal steps = qty.divide(DEFAULT_QTY_STEP, 0, RoundingMode.DOWN);
        return steps.multiply(DEFAULT_QTY_STEP);
    }

    private boolean isStepValid(BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }

        BigDecimal steps = qty.divide(DEFAULT_QTY_STEP, 0, RoundingMode.DOWN);
        BigDecimal rebuilt = steps.multiply(DEFAULT_QTY_STEP).setScale(7, RoundingMode.DOWN);

        return rebuilt.compareTo(qty.setScale(7, RoundingMode.DOWN)) == 0;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static class FillProcessingResult {
        final BigDecimal totalQty;
        final BigDecimal totalQuote;
        final BigDecimal totalFee;
        final String feeCurrency;
        final BigDecimal avgPrice;

        FillProcessingResult(
                BigDecimal totalQty,
                BigDecimal totalQuote,
                BigDecimal totalFee,
                String feeCurrency,
                BigDecimal avgPrice
        ) {
            this.totalQty = totalQty;
            this.totalQuote = totalQuote;
            this.totalFee = totalFee;
            this.feeCurrency = feeCurrency;
            this.avgPrice = avgPrice;
        }
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
        final BigDecimal normalizedQty;
        final BigDecimal estimatedPrice;
        final String plannedMode;

        private PreTradeValidationResult(
                boolean valid,
                String reason,
                BigDecimal estimatedQty,
                BigDecimal normalizedQty,
                BigDecimal estimatedPrice,
                String plannedMode
        ) {
            this.valid = valid;
            this.reason = reason;
            this.estimatedQty = estimatedQty;
            this.normalizedQty = normalizedQty;
            this.estimatedPrice = estimatedPrice;
            this.plannedMode = plannedMode;
        }

        static PreTradeValidationResult accepted(
                BigDecimal estimatedQty,
                BigDecimal normalizedQty,
                BigDecimal estimatedPrice,
                String plannedMode
        ) {
            return new PreTradeValidationResult(
                    true,
                    null,
                    estimatedQty,
                    normalizedQty,
                    estimatedPrice,
                    plannedMode
            );
        }

        static PreTradeValidationResult invalid(String reason) {
            return new PreTradeValidationResult(
                    false,
                    reason,
                    null,
                    null,
                    null,
                    null
            );
        }
    }
}