package id.co.blackheart.util;

import id.co.blackheart.dto.request.BinanceOrderRequest;
import id.co.blackheart.dto.response.BinanceOrderFill;
import id.co.blackheart.dto.response.BinanceOrderResponse;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.tradeexecuition.TradeExecutionService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@AllArgsConstructor
@Slf4j
public class TradeUtil {

    private static final BigDecimal MIN_USDT_NOTIONAL = new BigDecimal("7");

    /**
     * Quantity validation
     * 0.00011 = valid
     * 0.000115 = invalid
     */
    private static final BigDecimal DEFAULT_QTY_STEP = new BigDecimal("0.00001");

    /**
     * Minimum qty allowed for each child trade position after split.
     * If any split result is below this, downgrade the split structure.
     */
    private static final BigDecimal MIN_POSITION_QTY = new BigDecimal("0.0001");

    private static final BigDecimal BUY_PRICE_BUFFER = new BigDecimal("1.001");
    private static final BigDecimal SELL_PRICE_BUFFER = new BigDecimal("0.999");

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_PARTIALLY_CLOSED = "PARTIALLY_CLOSED";

    private static final String EXIT_STRUCTURE_SINGLE = "SINGLE";
    private static final String EXIT_STRUCTURE_TP1_RUNNER = "TP1_RUNNER";
    private static final String EXIT_STRUCTURE_TP1_TP2_RUNNER = "TP1_TP2_RUNNER";
    private static final String EXIT_STRUCTURE_RUNNER_ONLY = "RUNNER_ONLY";

    private static final String TARGET_ALL = "ALL";

    private final TradesRepository tradesRepository;
    private final TradePositionRepository tradePositionRepository;
    private final TradeExecutionService tradeExecutionService;

    public enum TradeType {
        LONG, SHORT
    }

    @Transactional
    public void binanceOpenLongMarketOrder(
            StrategyContext context,
            StrategyDecision decision,
            BigDecimal tradeAmount
    ) {
        openParentTrade(context, decision, tradeAmount, TradeType.LONG, context.getAsset());
    }

    @Transactional
    public void binanceOpenShortMarketOrder(
            StrategyContext context,
            String asset,
            StrategyDecision decision,
            BigDecimal tradeAmount
    ) {
        openParentTrade(context, decision, tradeAmount, TradeType.SHORT, asset);
    }


    @Transactional
    public void binanceCloseLongPositionsMarketOrder(Users user,List<TradePosition> tradePositions,String asset) {
        closeGroupedPositions(user, tradePositions, asset, TradeType.LONG);
    }

    @Transactional
    public void binanceCloseShortPositionsMarketOrder(Users user,List<TradePosition> tradePositions,String asset) {
        closeGroupedPositions(user, tradePositions, asset, TradeType.SHORT);
    }

    private void closeGroupedPositions(Users user,List<TradePosition> tradePositions,String asset,TradeType tradeType) {
        if (user == null || tradePositions == null || tradePositions.isEmpty()) {
            return;
        }

        List<TradePosition> validOpenPositions = tradePositions.stream()
                .filter(tp -> tp != null)
                .filter(tp -> "OPEN".equalsIgnoreCase(tp.getStatus()))
                .filter(tp -> tp.getRemainingQty() != null && tp.getRemainingQty().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (validOpenPositions.isEmpty()) {
            return;
        }

        TradePosition first = validOpenPositions.getFirst();

        BigDecimal totalQty = validOpenPositions.stream()
                .map(TradePosition::getRemainingQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal closeQty = floorToStep(totalQty);

        if (closeQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Grouped close qty became zero after normalization | tradeId={} asset={}",
                    first.getTradeId(), asset);
            return;
        }

        try {
            String orderSide = tradeType == TradeType.LONG ? "SELL" : "BUY";

            BinanceOrderRequest request = BinanceOrderRequest.builder()
                    .symbol(asset)
                    .side(orderSide)
                    .amount(closeQty)
                    .apiKey(user.getApiKey())
                    .apiSecret(user.getApiSecret())
                    .build();

            BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(request);
            FillProcessingResult result = processOrderFills(response);

            applyGroupedExitToPositions(validOpenPositions, result, tradeType);

            tradePositionRepository.saveAll(validOpenPositions);
            refreshParentTradeSummary(first.getTradeId());

            log.info("✅ Grouped {} close success | tradeId={} asset={} positions={} qty={} avgExitPrice={}",
                    tradeType,
                    first.getTradeId(),
                    asset,
                    validOpenPositions.size(),
                    closeQty,
                    result.avgPrice);

        } catch (Exception e) {
            log.error("❌ Error closing grouped {} positions | tradeId={} asset={} positions={}",
                    tradeType,
                    first.getTradeId(),
                    asset,
                    validOpenPositions.size(),
                    e);
        }
    }

    private void applyGroupedExitToPositions(List<TradePosition> positions,FillProcessingResult result,TradeType tradeType) {
        BigDecimal totalQty = positions.stream()
                .map(TradePosition::getRemainingQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal allocatedFeeRunning = BigDecimal.ZERO;
        BigDecimal allocatedQuoteRunning = BigDecimal.ZERO;
        BigDecimal allocatedQtyRunning = BigDecimal.ZERO;

        for (int i = 0; i < positions.size(); i++) {
            TradePosition position = positions.get(i);

            BigDecimal exitQty;
            BigDecimal exitQuoteQty;
            BigDecimal exitFee;

            if (i == positions.size() - 1) {
                exitQty = result.totalQty.subtract(allocatedQtyRunning);
                exitQuoteQty = result.totalQuote.subtract(allocatedQuoteRunning);
                exitFee = safe(result.totalFee).subtract(allocatedFeeRunning);
            } else {
                BigDecimal ratio = position.getRemainingQty()
                        .divide(totalQty, 12, RoundingMode.HALF_UP);

                exitQty = result.totalQty.multiply(ratio).setScale(8, RoundingMode.DOWN);
                exitQuoteQty = result.totalQuote.multiply(ratio).setScale(8, RoundingMode.DOWN);
                exitFee = safe(result.totalFee).multiply(ratio).setScale(8, RoundingMode.DOWN);

                allocatedQtyRunning = allocatedQtyRunning.add(exitQty);
                allocatedQuoteRunning = allocatedQuoteRunning.add(exitQuoteQty);
                allocatedFeeRunning = allocatedFeeRunning.add(exitFee);
            }

            BigDecimal exitPrice = exitQty.compareTo(BigDecimal.ZERO) > 0
                    ? exitQuoteQty.divide(exitQty, 8, RoundingMode.HALF_UP)
                    : result.avgPrice;

            position.setExitExecutedQty(exitQty);
            position.setExitExecutedQuoteQty(exitQuoteQty);
            position.setExitFee(exitFee);
            position.setExitFeeCurrency(result.feeCurrency);
            position.setExitPrice(exitPrice);
            position.setExitTime(LocalDateTime.now());
            position.setStatus("CLOSED");
            position.setRemainingQty(BigDecimal.ZERO);

            BigDecimal pnlAmount = calculatePLAmount(
                    position.getEntryPrice(),
                    exitPrice,
                    position.getEntryQty(),
                    tradeType
            );

            BigDecimal pnlPercent = calculatePLPercentage(
                    position.getEntryPrice(),
                    exitPrice,
                    tradeType
            );

            position.setRealizedPnlAmount(pnlAmount);
            position.setRealizedPnlPercent(pnlPercent);
        }
    }

    @Transactional
    public void binanceCloseLongPositionMarketOrder(
            Users user,
            TradePosition tradePosition,
            String asset
    ) {
        TradePosition validated = validateClosePositionInputs(user, tradePosition, asset, TradeType.LONG);
        if (validated == null) {
            return;
        }

        try {
            BigDecimal closeQty = floorToStep(validated.getRemainingQty());

            if (closeQty.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Close LONG qty became zero after normalization | tradePositionId={}",
                        validated.getTradePositionId());
                return;
            }

            if (!isStepValid(closeQty)) {
                log.warn("Close LONG qty invalid after normalization | tradePositionId={} qty={}",
                        validated.getTradePositionId(), closeQty);
                return;
            }

            if (closeQty.compareTo(validated.getRemainingQty()) != 0) {
                log.warn("Normalized LONG close qty | tradePositionId={} storedQty={} normalizedQty={}",
                        validated.getTradePositionId(),
                        validated.getRemainingQty(),
                        closeQty);
            }

            BinanceOrderRequest request = BinanceOrderRequest.builder()
                    .symbol(asset)
                    .side("SELL")
                    .amount(closeQty)
                    .apiKey(user.getApiKey())
                    .apiSecret(user.getApiSecret())
                    .build();

            BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(request);
            FillProcessingResult result = processOrderFills(response);

            updateTradePositionWithExitData(validated, result, TradeType.LONG);
            tradePositionRepository.save(validated);

            refreshParentTradeSummary(validated.getTradeId());

            log.info(
                    "✅ LONG trade position closed | tradePositionId={} asset={} avgExitPrice={}",
                    validated.getTradePositionId(),
                    asset,
                    result.avgPrice
            );

        } catch (Exception e) {
            log.error("❌ Error closing LONG trade position for {}", asset, e);
        }
    }

    @Transactional
    public void binanceCloseShortPositionMarketOrder(
            Users user,
            TradePosition tradePosition,
            String asset
    ) {
        TradePosition validated = validateClosePositionInputs(user, tradePosition, asset, TradeType.SHORT);
        if (validated == null) {
            return;
        }

        try {
            BigDecimal closeQty = floorToStep(validated.getRemainingQty());

            if (closeQty.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Close SHORT qty became zero after normalization | tradePositionId={}",
                        validated.getTradePositionId());
                return;
            }

            if (!isStepValid(closeQty)) {
                log.warn("Close SHORT qty invalid after normalization | tradePositionId={} qty={}",
                        validated.getTradePositionId(), closeQty);
                return;
            }

            if (closeQty.compareTo(validated.getRemainingQty()) != 0) {
                log.warn("Normalized SHORT close qty | tradePositionId={} storedQty={} normalizedQty={}",
                        validated.getTradePositionId(),
                        validated.getRemainingQty(),
                        closeQty);
            }

            BinanceOrderRequest request = BinanceOrderRequest.builder()
                    .symbol(asset)
                    .side("BUY")
                    .amount(closeQty)
                    .apiKey(user.getApiKey())
                    .apiSecret(user.getApiSecret())
                    .build();

            BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(request);
            FillProcessingResult result = processOrderFills(response);

            updateTradePositionWithExitData(validated, result, TradeType.SHORT);
            tradePositionRepository.save(validated);

            refreshParentTradeSummary(validated.getTradeId());

            log.info(
                    "✅ SHORT trade position closed | tradePositionId={} asset={} avgExitPrice={}",
                    validated.getTradePositionId(),
                    asset,
                    result.avgPrice
            );

        } catch (Exception e) {
            log.error("❌ Error closing SHORT trade position for {}", asset, e);
        }
    }

    @Transactional
    public void updateOpenTradePositions(
            Trades activeTrade,
            StrategyDecision decision
    ) {
        if (activeTrade == null || decision == null) {
            return;
        }

        List<TradePosition> openPositions = tradePositionRepository.findAllByTradeIdAndStatus(
                activeTrade.getTradeId(),
                STATUS_OPEN
        );

        if (openPositions.isEmpty()) {
            return;
        }

        String targetRole = normalizeTargetRole(decision.getTargetPositionRole());

        for (TradePosition position : openPositions) {
            if (!shouldApplyToRole(position, targetRole)) {
                continue;
            }

            if (decision.getStopLossPrice() != null) {
                position.setCurrentStopLossPrice(decision.getStopLossPrice());
            }

            if (decision.getTrailingStopPrice() != null) {
                position.setTrailingStopPrice(decision.getTrailingStopPrice());
            }

            BigDecimal updatedTakeProfit = resolveUpdatedTakeProfitForRole(position, decision);
            if (updatedTakeProfit != null || "RUNNER".equalsIgnoreCase(position.getPositionRole())) {
                position.setTakeProfitPrice(updatedTakeProfit);
            }

            position.setExitReason("STRATEGY_MANAGEMENT_UPDATE");
        }

        tradePositionRepository.saveAll(openPositions);

        log.info(
                "✅ Updated open positions | tradeId={} targetRole={} stop={} trailing={} tp1={} tp2={} tp3={}",
                activeTrade.getTradeId(),
                targetRole,
                decision.getStopLossPrice(),
                decision.getTrailingStopPrice(),
                decision.getTakeProfitPrice1(),
                decision.getTakeProfitPrice2(),
                decision.getTakeProfitPrice3()
        );
    }

    private void openParentTrade(
            StrategyContext context,
            StrategyDecision decision,
            BigDecimal tradeAmount,
            TradeType tradeType,
            String asset
    ) {
        Trades persistedTrade = null;

        try {
            PreTradeValidationResult validation = validateBeforeOpen(context, decision, tradeAmount, tradeType, asset);
            if (!validation.valid) {
                log.warn(
                        "🚫 {} trade rejected before execution | asset={} amount={} reason={}",
                        tradeType,
                        asset,
                        tradeAmount,
                        validation.reason
                );
                return;
            }

            String orderSide = tradeType == TradeType.LONG ? "BUY" : "SELL";

            log.info(
                    "Opening {} parent trade | asset={} amount={} estimatedPrice={} estimatedQty={} plannedMode={}",
                    tradeType,
                    asset,
                    tradeAmount,
                    validation.estimatedPrice,
                    validation.estimatedQty,
                    validation.plannedMode
            );

            BinanceOrderRequest request = BinanceOrderRequest.builder()
                    .symbol(asset)
                    .side(orderSide)
                    .amount(tradeAmount)
                    .apiKey(context.getUser().getApiKey())
                    .apiSecret(context.getUser().getApiSecret())
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
                    .userId(context.getUser().getUserId())
                    .userStrategyId(context.getUserStrategyId())
                    .strategyName(context.getStrategyCode())
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
                    .build();

            tradesRepository.save(persistedTrade);

            SplitPlan finalPlan = buildSplitPlan(normalizedExecutedQty, avgEntryPrice, decision);

            if (isInvalidPlan(finalPlan)) {
                persistedTrade.setTradeMode("UNALLOCATED");
                persistedTrade.setExitReason("EXIT_PLAN_PENDING");
                tradesRepository.save(persistedTrade);

                log.error(
                        "❌ Unable to allocate any valid exit structure | tradeId={} asset={} qty={} avgEntryPrice={}",
                        persistedTrade.getTradeId(),
                        asset,
                        normalizedExecutedQty,
                        avgEntryPrice
                );
                return;
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

            log.info(
                    "✅ {} parent trade created | tradeId={} asset={} avgEntryPrice={} totalQty={} mode={}",
                    tradeType,
                    persistedTrade.getTradeId(),
                    asset,
                    avgEntryPrice,
                    normalizedExecutedQty,
                    finalPlan.tradeMode
            );

        } catch (Exception e) {
            log.error("❌ Error placing {} parent trade for {}", tradeType, asset, e);

            if (persistedTrade != null) {
                try {
                    persistedTrade.setTradeMode("UNALLOCATED");
                    persistedTrade.setExitReason("EXIT_PLAN_PENDING");
                    tradesRepository.save(persistedTrade);
                } catch (Exception saveEx) {
                    log.error("Failed to update trade state after entry error | asset={}", asset, saveEx);
                }
            }
        }
    }

    private PreTradeValidationResult validateBeforeOpen(
            StrategyContext context,
            StrategyDecision decision,
            BigDecimal tradeAmount,
            TradeType tradeType,
            String asset
    ) {
        if (context == null) {
            return PreTradeValidationResult.invalid("StrategyContext is null");
        }

        if (context.getUser() == null) {
            return PreTradeValidationResult.invalid("User is null");
        }

        if (asset == null || asset.isBlank()) {
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
        BigDecimal estimatedQty = floorToStep(
                tradeAmount.divide(bufferedPrice, 12, RoundingMode.DOWN)
        );

        if (estimatedQty.compareTo(BigDecimal.ZERO) <= 0) {
            return PreTradeValidationResult.invalid("Estimated quantity is zero after step normalization");
        }

        if (!isStepValid(estimatedQty)) {
            return PreTradeValidationResult.invalid("Estimated quantity is invalid for step size");
        }

        if (estimatedQty.compareTo(MIN_POSITION_QTY) < 0) {
            return PreTradeValidationResult.invalid("Estimated quantity below minimum position quantity");
        }

        BigDecimal estimatedNotional = estimatedQty.multiply(bufferedPrice);
        if (estimatedNotional.compareTo(MIN_USDT_NOTIONAL) < 0) {
            return PreTradeValidationResult.invalid(
                    String.format(
                            "Estimated notional below minimum notional. min=%s, estimated=%s",
                            MIN_USDT_NOTIONAL,
                            estimatedNotional
                    )
            );
        }

        SplitPlan estimatedPlan = buildSplitPlan(estimatedQty, bufferedPrice, decision);
        if (isInvalidPlan(estimatedPlan)) {
            return PreTradeValidationResult.invalid("No valid exit structure can be generated for estimated quantity");
        }

        return PreTradeValidationResult.valid(
                estimatedQty,
                bufferedPrice,
                estimatedPlan.tradeMode
        );
    }

    private BigDecimal resolveReferencePrice(StrategyDecision decision, TradeType tradeType) {
        BigDecimal tp1 = decision.getTakeProfitPrice1();
        BigDecimal tp2 = decision.getTakeProfitPrice2();
        BigDecimal stop = decision.getStopLossPrice();

        if (tradeType == TradeType.LONG) {
            if (tp1 != null && stop != null && tp1.compareTo(stop) > 0) {
                return tp1.add(stop).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
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

        if (tp1 != null && stop != null && stop.compareTo(tp1) > 0) {
            return tp1.add(stop).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
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

    private TradePosition validateClosePositionInputs(
            Users user,
            TradePosition tradePosition,
            String asset,
            TradeType tradeType
    ) {
        if (user == null) {
            log.info("User data is null");
            return null;
        }

        if (tradePosition == null) {
            log.info("Trade position is null for {}", asset);
            return null;
        }

        if (!STATUS_OPEN.equalsIgnoreCase(tradePosition.getStatus())) {
            log.info("Trade position already not OPEN | tradePositionId={}", tradePosition.getTradePositionId());
            return null;
        }

        if (tradePosition.getRemainingQty() == null || tradePosition.getRemainingQty().compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Remaining quantity invalid | tradePositionId={}", tradePosition.getTradePositionId());
            return null;
        }

        String expectedSide = tradeType == TradeType.LONG ? "LONG" : "SHORT";
        if (!expectedSide.equalsIgnoreCase(tradePosition.getSide())) {
            log.info(
                    "Trade position side mismatch | tradePositionId={} expected={} actual={}",
                    tradePosition.getTradePositionId(),
                    expectedSide,
                    tradePosition.getSide()
            );
            return null;
        }

        return tradePosition;
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
                    .userId(trade.getUserId())
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

            tradePositionRepository.save(tradePosition);
        }
    }

    private FillProcessingResult processOrderFills(BinanceOrderResponse response) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalQuote = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        String feeCurrency = null;

        if (response.getFills() != null && !response.getFills().isEmpty()) {
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

    private void updateTradePositionWithExitData(
            TradePosition tradePosition,
            FillProcessingResult result,
            TradeType tradeType
    ) {
        tradePosition.setExitExecutedQty(result.totalQty);
        tradePosition.setExitExecutedQuoteQty(result.totalQuote);
        tradePosition.setExitFee(result.totalFee);
        tradePosition.setExitFeeCurrency(result.feeCurrency);
        tradePosition.setExitPrice(result.avgPrice);
        tradePosition.setExitTime(LocalDateTime.now());
        tradePosition.setStatus(STATUS_CLOSED);
        tradePosition.setRemainingQty(BigDecimal.ZERO);

        BigDecimal pnlAmount = calculatePLAmount(
                tradePosition.getEntryPrice(),
                result.avgPrice,
                tradePosition.getEntryQty(),
                tradeType
        );

        BigDecimal pnlPercent = calculatePLPercentage(
                tradePosition.getEntryPrice(),
                result.avgPrice,
                tradeType
        );

        tradePosition.setRealizedPnlAmount(pnlAmount);
        tradePosition.setRealizedPnlPercent(pnlPercent);
    }

    private void refreshParentTradeSummary(UUID tradeId) {
        Trades trade = tradesRepository.findByTradeId(tradeId).orElse(null);
        if (trade == null) {
            return;
        }

        List<TradePosition> allPositions = tradePositionRepository.findAllByTradeId(tradeId);
        if (allPositions.isEmpty()) {
            return;
        }

        BigDecimal totalRemainingQty = allPositions.stream()
                .map(TradePosition::getRemainingQty)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal realizedPnlAmount = allPositions.stream()
                .map(TradePosition::getRealizedPnlAmount)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalFeeAmount = allPositions.stream()
                .map(tp -> safe(tp.getEntryFee()).add(safe(tp.getExitFee())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<TradePosition> closedPositions = allPositions.stream()
                .filter(tp -> STATUS_CLOSED.equalsIgnoreCase(tp.getStatus()))
                .toList();

        BigDecimal avgExitPrice = null;
        if (!closedPositions.isEmpty()) {
            BigDecimal totalClosedQty = closedPositions.stream()
                    .map(TradePosition::getExitExecutedQty)
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

        if (trade.getTotalEntryQuoteQty() != null
                && trade.getTotalEntryQuoteQty().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pnlPercent = realizedPnlAmount
                    .divide(trade.getTotalEntryQuoteQty(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            trade.setRealizedPnlPercent(pnlPercent);
        }

        if (openCount == 0) {
            trade.setStatus(STATUS_CLOSED);
            trade.setExitTime(LocalDateTime.now());

            TradePosition latestClosed = closedPositions.stream()
                    .max(Comparator.comparing(TradePosition::getExitTime, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);

            if (latestClosed != null) {
                trade.setExitReason(latestClosed.getExitReason());
            }
        } else if (openCount < allPositions.size()) {
            trade.setStatus(STATUS_PARTIALLY_CLOSED);
        } else {
            trade.setStatus(STATUS_OPEN);
        }

        tradesRepository.save(trade);
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

    private String normalizeExitStructure(String exitStructure) {
        if (exitStructure == null || exitStructure.isBlank()) {
            return EXIT_STRUCTURE_SINGLE;
        }
        return exitStructure.trim().toUpperCase();
    }

    private String normalizeTargetRole(String targetRole) {
        if (targetRole == null || targetRole.isBlank()) {
            return TARGET_ALL;
        }
        return targetRole.trim().toUpperCase();
    }

    private boolean shouldApplyToRole(TradePosition position, String targetRole) {
        if (TARGET_ALL.equalsIgnoreCase(targetRole)) {
            return true;
        }
        if (position == null || position.getPositionRole() == null) {
            return false;
        }
        return targetRole.equalsIgnoreCase(position.getPositionRole());
    }

    private BigDecimal resolveUpdatedTakeProfitForRole(TradePosition position, StrategyDecision decision) {
        if (position == null || position.getPositionRole() == null) {
            return decision.getTakeProfitPrice1();
        }

        return switch (position.getPositionRole().toUpperCase()) {
            case "SINGLE", "TP1" -> decision.getTakeProfitPrice1();
            case "TP2" -> decision.getTakeProfitPrice2();
            case "TP3" -> decision.getTakeProfitPrice3();
            case "RUNNER" -> null;
            default -> decision.getTakeProfitPrice1();
        };
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
            log.error("Three-slice allocation mismatch | totalQty={} allocated={}",
                    normalizedTotalQty, allocatedTotal);
            return invalidPlan();
        }

        if (!isClosableSlice(tp1Qty, avgEntryPrice)
                || !isClosableSlice(tp2Qty, avgEntryPrice)
                || !isClosableSlice(runnerQty, avgEntryPrice)) {
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
                                "RUNNER",
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
            log.error("Two-slice allocation mismatch | totalQty={} allocated={}",
                    normalizedTotalQty, allocatedTotal);
            return invalidPlan();
        }

        if (!isClosableSlice(tp1Qty, avgEntryPrice) || !isClosableSlice(runnerQty, avgEntryPrice)) {
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
                                "RUNNER",
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

        if (!isClosableSlice(runnerQty, avgEntryPrice)) {
            return invalidPlan();
        }

        return new SplitPlan(
                EXIT_STRUCTURE_RUNNER_ONLY,
                List.of(
                        PlannedPosition.of(
                                "RUNNER",
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

        if (!isClosableSlice(singleQty, avgEntryPrice)) {
            return invalidPlan();
        }

        BigDecimal singleTp = decision.getTakeProfitPrice1() != null
                ? decision.getTakeProfitPrice1()
                : null;

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

    private boolean isClosableSlice(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) {
            return false;
        }

        if (!isStepValid(qty)) {
            return false;
        }

        if (qty.compareTo(MIN_POSITION_QTY) < 0) {
            return false;
        }

        BigDecimal notional = qty.multiply(price);
        return notional.compareTo(MIN_USDT_NOTIONAL) >= 0;
    }

    private boolean isInvalidPlan(SplitPlan splitPlan) {
        return splitPlan == null || splitPlan.positions == null || splitPlan.positions.isEmpty();
    }

    private SplitPlan invalidPlan() {
        return new SplitPlan("INVALID", List.of());
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

    private BigDecimal calculatePLPercentage(
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            TradeType tradeType
    ) {
        if (entryPrice == null || exitPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal diff = switch (tradeType) {
            case LONG -> exitPrice.subtract(entryPrice);
            case SHORT -> entryPrice.subtract(exitPrice);
        };

        return diff.divide(entryPrice, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private BigDecimal floorToStep(BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal steps = qty.divide(DEFAULT_QTY_STEP, 0, RoundingMode.DOWN);
        return steps.multiply(DEFAULT_QTY_STEP).stripTrailingZeros();
    }

    private boolean isStepValid(BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        return qty.remainder(DEFAULT_QTY_STEP).compareTo(BigDecimal.ZERO) == 0;
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

        static PreTradeValidationResult valid(
                BigDecimal estimatedQty,
                BigDecimal estimatedPrice,
                String plannedMode
        ) {
            return new PreTradeValidationResult(
                    true,
                    null,
                    estimatedQty,
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
                    null
            );
        }
    }
}