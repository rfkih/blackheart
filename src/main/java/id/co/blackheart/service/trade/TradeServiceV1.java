package id.co.blackheart.service.trade;

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
import id.co.blackheart.service.cache.CacheService;
import id.co.blackheart.service.redis.RedisPublisher;
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
public class TradeServiceV1 {

    private static final BigDecimal MIN_USDT_NOTIONAL = new BigDecimal("7");
    private static final BigDecimal DEFAULT_QTY_STEP = new BigDecimal("0.00001");
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
    private final CacheService cacheService;
    private final RedisPublisher redisPublisher;

    public enum TradeType {
        LONG, SHORT
    }

    @Transactional
    public void binanceOpenLongMarketOrder(
            StrategyContext context,
            StrategyDecision decision,
            BigDecimal tradeAmount
    ) {
        openMarketOrder(context, decision, tradeAmount, TradeType.LONG, context.getAsset());
    }

    @Transactional
    public void binanceOpenShortMarketOrder(
            StrategyContext context,
            StrategyDecision decision,
            BigDecimal tradeAmount,
            String asset
    ) {
        openMarketOrder(context, decision, tradeAmount, TradeType.SHORT, asset);
    }

    @Transactional
    public void binanceCloseLongPositionsMarketOrder(
            Users user,
            List<TradePosition> tradePositions,
            String asset
    ) {
        closeGroupedPositions(user, tradePositions, asset, TradeType.LONG);
    }

    @Transactional
    public void binanceCloseShortPositionsMarketOrder(
            Users user,
            List<TradePosition> tradePositions,
            String asset
    ) {
        closeGroupedPositions(user, tradePositions, asset, TradeType.SHORT);
    }

    @Transactional
    public void binanceCloseLongPositionMarketOrder(
            Users user,
            TradePosition tradePosition,
            String asset
    ) {
        closeSinglePosition(user, tradePosition, asset, TradeType.LONG);
    }

    @Transactional
    public void binanceCloseShortPositionMarketOrder(
            Users user,
            TradePosition tradePosition,
            String asset
    ) {
        closeSinglePosition(user, tradePosition, asset, TradeType.SHORT);
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

            applyManagementUpdate(position, decision);
        }

        tradePositionRepository.saveAll(openPositions);
        syncTradeState(activeTrade.getTradeId());

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

    private void openMarketOrder(
            StrategyContext context,
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

        syncTradeState(tradeId);
    }

    private void closeSinglePosition(
            Users user,
            TradePosition tradePosition,
            String asset,
            TradeType tradeType
    ) {
        TradePosition validated = validateClosePositionInputs(user, tradePosition, asset, tradeType);
        if (validated == null) {
            return;
        }

        try {
            BigDecimal closeQty = normalizeCloseQty(validated);
            BigDecimal requestAmount = buildSingleCloseRequestAmount(validated, closeQty, tradeType);

            BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(
                    buildOrderRequest(asset, resolveCloseOrderSide(tradeType), requestAmount, user)
            );

            FillProcessingResult result = processOrderFills(response);

            updateTradePositionWithExitData(validated, result, tradeType);
            tradePositionRepository.save(validated);

            refreshParentTradeSummary(validated.getTradeId());
            syncTradeState(validated.getTradeId());

            log.info(
                    "✅ {} trade position closed | tradePositionId={} asset={} avgExitPrice={}",
                    tradeType,
                    validated.getTradePositionId(),
                    asset,
                    result.avgPrice
            );

        } catch (Exception e) {
            log.error(
                    "❌ Error closing {} trade position | tradePositionId={} asset={}",
                    tradeType,
                    tradePosition != null ? tradePosition.getTradePositionId() : null,
                    asset,
                    e
            );
        }
    }

    private void closeGroupedPositions(
            Users user,
            List<TradePosition> tradePositions,
            String asset,
            TradeType tradeType
    ) {
        if (user == null || tradePositions == null || tradePositions.isEmpty()) {
            return;
        }

        List<TradePosition> validOpenPositions = tradePositions.stream()
                .filter(this::isClosableOpenPosition)
                .toList();

        if (validOpenPositions.isEmpty()) {
            return;
        }

        TradePosition first = validOpenPositions.getFirst();

        try {
            BigDecimal totalQty = validOpenPositions.stream()
                    .map(TradePosition::getRemainingQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal closeQty = floorToStep(totalQty);
            if (closeQty.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn(
                        "Grouped close qty became zero after normalization | tradeId={} asset={}",
                        first.getTradeId(),
                        asset
                );
                return;
            }

            BigDecimal requestAmount = buildGroupedCloseRequestAmount(validOpenPositions, closeQty, tradeType, asset);

            BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(
                    buildOrderRequest(asset, resolveCloseOrderSide(tradeType), requestAmount, user)
            );

            FillProcessingResult result = processOrderFills(response);

            applyGroupedExitToPositions(validOpenPositions, result, tradeType);
            tradePositionRepository.saveAll(validOpenPositions);

            refreshParentTradeSummary(first.getTradeId());
            syncTradeState(first.getTradeId());

            log.info(
                    "✅ Grouped {} close success | tradeId={} asset={} positions={} requestAmount={} avgExitPrice={}",
                    tradeType,
                    first.getTradeId(),
                    asset,
                    validOpenPositions.size(),
                    requestAmount,
                    result.avgPrice
            );

        } catch (Exception e) {
            log.error(
                    "❌ Error closing grouped {} positions | tradeId={} asset={} positions={}",
                    tradeType,
                    first.getTradeId(),
                    asset,
                    validOpenPositions.size(),
                    e
            );
        }
    }

    private void syncTradeState(UUID tradeId) {
        try {
            Trades trade = tradesRepository.findById(tradeId)
                    .orElseThrow(() -> new IllegalStateException("Trade not found after persistence"));

            if (STATUS_CLOSED.equalsIgnoreCase(trade.getStatus())) {
                cacheService.removeClosedTrade(trade.getUserId(), tradeId);
                redisPublisher.publishTradeStateChange(tradeId, STATUS_CLOSED);
                return;
            }

            List<TradePosition> openPositions =
                    tradePositionRepository.findAllByTradeIdAndStatus(tradeId, STATUS_OPEN);

            cacheService.cacheUserActiveTrade(
                    trade.getUserId(),
                    trade.getTradeId(),
                    trade,
                    openPositions
            );

            redisPublisher.publishTradeStateChange(tradeId, trade.getStatus());

        } catch (Exception e) {
            log.error("Failed to sync trade state | tradeId={}", tradeId, e);
        }
    }

    private BinanceOrderRequest buildOrderRequest(
            String asset,
            String side,
            BigDecimal amount,
            Users user
    ) {
        return BinanceOrderRequest.builder()
                .symbol(asset)
                .side(side)
                .amount(amount)
                .apiKey(user.getApiKey())
                .apiSecret(user.getApiSecret())
                .build();
    }

    private String resolveCloseOrderSide(TradeType tradeType) {
        return tradeType == TradeType.LONG ? "SELL" : "BUY";
    }

    private BigDecimal buildSingleCloseRequestAmount(
            TradePosition position,
            BigDecimal closeQty,
            TradeType tradeType
    ) {
        if (tradeType == TradeType.LONG) {
            return closeQty;
        }

        BigDecimal referencePrice = resolveCloseReferencePrice(List.of(position));
        BigDecimal buyNotionalUsdt = closeQty.multiply(referencePrice).setScale(8, RoundingMode.UP);

        if (buyNotionalUsdt.compareTo(MIN_USDT_NOTIONAL) < 0) {
            buyNotionalUsdt = MIN_USDT_NOTIONAL;
        }

        log.info(
                "SHORT close sizing | tradePositionId={} remainingQty={} closeQty={} referencePrice={} buyNotionalUsdt={}",
                position.getTradePositionId(),
                position.getRemainingQty(),
                closeQty,
                referencePrice,
                buyNotionalUsdt
        );

        return buyNotionalUsdt;
    }

    private BigDecimal buildGroupedCloseRequestAmount(
            List<TradePosition> positions,
            BigDecimal closeQty,
            TradeType tradeType,
            String asset
    ) {
        if (tradeType == TradeType.LONG) {
            return closeQty;
        }

        TradePosition first = positions.getFirst();

        BigDecimal totalQty = positions.stream()
                .map(TradePosition::getRemainingQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal referencePrice = resolveCloseReferencePrice(positions);
        BigDecimal requestAmount = closeQty.multiply(referencePrice).setScale(8, RoundingMode.UP);

        if (requestAmount.compareTo(MIN_USDT_NOTIONAL) < 0) {
            requestAmount = MIN_USDT_NOTIONAL;
        }

        log.info(
                "Grouped SHORT close sizing | tradeId={} asset={} totalQty={} closeQty={} referencePrice={} buyNotionalUsdt={}",
                first.getTradeId(),
                asset,
                totalQty,
                closeQty,
                referencePrice,
                requestAmount
        );

        return requestAmount;
    }

    private boolean isClosableOpenPosition(TradePosition tradePosition) {
        return tradePosition != null
                && STATUS_OPEN.equalsIgnoreCase(tradePosition.getStatus())
                && tradePosition.getRemainingQty() != null
                && tradePosition.getRemainingQty().compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal normalizeCloseQty(TradePosition position) {
        BigDecimal closeQty = floorToStep(position.getRemainingQty());

        if (closeQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException(
                    "Close quantity became zero after normalization | tradePositionId=" + position.getTradePositionId()
            );
        }

        if (!isStepValid(closeQty)) {
            throw new IllegalStateException(
                    "Close quantity invalid after normalization | tradePositionId=" + position.getTradePositionId()
                            + ", qty=" + closeQty
            );
        }

        if (closeQty.compareTo(position.getRemainingQty()) != 0) {
            log.warn(
                    "Normalized close qty | tradePositionId={} storedQty={} normalizedQty={}",
                    position.getTradePositionId(),
                    position.getRemainingQty(),
                    closeQty
            );
        }

        return closeQty;
    }

    private void applyManagementUpdate(TradePosition position, StrategyDecision decision) {
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

    private BigDecimal resolveCloseReferencePrice(List<TradePosition> positions) {
        if (positions == null || positions.isEmpty()) {
            throw new IllegalStateException("No trade positions available to resolve close reference price");
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal weightedPriceSum = BigDecimal.ZERO;

        for (TradePosition position : positions) {
            if (position == null) {
                continue;
            }

            BigDecimal qty = safe(position.getRemainingQty());
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal referencePrice = safe(position.getEntryPrice());

            BigDecimal currentStop = position.getCurrentStopLossPrice();
            BigDecimal trailingStop = position.getTrailingStopPrice();
            BigDecimal takeProfit = position.getTakeProfitPrice();

            if (currentStop != null && currentStop.compareTo(BigDecimal.ZERO) > 0) {
                referencePrice = currentStop;
            } else if (trailingStop != null && trailingStop.compareTo(BigDecimal.ZERO) > 0) {
                referencePrice = trailingStop;
            } else if (takeProfit != null && takeProfit.compareTo(BigDecimal.ZERO) > 0) {
                referencePrice = takeProfit;
            }

            weightedPriceSum = weightedPriceSum.add(referencePrice.multiply(qty));
            totalQty = totalQty.add(qty);
        }

        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Unable to resolve close reference price because total quantity is zero");
        }

        return weightedPriceSum.divide(totalQty, 8, RoundingMode.HALF_UP);
    }

    private UUID openParentTrade(
            StrategyContext context,
            StrategyDecision decision,
            BigDecimal tradeQuoteNotional,
            TradeType tradeType,
            String asset
    ) {
        Trades persistedTrade = null;

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
                return null;
            }

            String orderSide = tradeType == TradeType.LONG ? "BUY" : "SELL";
            BigDecimal requestAmount = tradeType == TradeType.LONG
                    ? tradeQuoteNotional
                    : validation.normalizedQty;

            log.info(
                    "Opening {} parent trade | asset={} quoteNotional={} estimatedPrice={} estimatedQty={} normalizedQty={} plannedMode={}",
                    tradeType,
                    asset,
                    tradeQuoteNotional,
                    validation.estimatedPrice,
                    validation.estimatedQty,
                    validation.normalizedQty,
                    validation.plannedMode
            );

            BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(
                    BinanceOrderRequest.builder()
                            .symbol(asset)
                            .side(orderSide)
                            .amount(requestAmount)
                            .apiKey(context.getUser().getApiKey())
                            .apiSecret(context.getUser().getApiSecret())
                            .build()
            );

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

            log.info(
                    "✅ {} parent trade created | tradeId={} asset={} avgEntryPrice={} totalQty={} mode={}",
                    tradeType,
                    persistedTrade.getTradeId(),
                    asset,
                    avgEntryPrice,
                    normalizedExecutedQty,
                    finalPlan.tradeMode
            );

            return persistedTrade.getTradeId();

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
                return persistedTrade.getTradeId();
            }

            return null;
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

        return PreTradeValidationResult.valid(
                estimatedQty,
                normalizedQty,
                bufferedPrice,
                estimatedPlan.tradeMode
        );
    }

    private BigDecimal resolveReferencePrice(StrategyDecision decision, TradeType tradeType) {
        BigDecimal tp1 = decision.getTakeProfitPrice1();
        BigDecimal tp2 = decision.getTakeProfitPrice2();
        BigDecimal stop = decision.getStopLossPrice();

        // Calculate midpoint between TP1 and stop loss
        if (tp1 != null && stop != null) {
            boolean isValidMidpoint = (tradeType == TradeType.LONG && tp1.compareTo(stop) > 0) ||
                    (tradeType != TradeType.LONG && stop.compareTo(tp1) > 0);

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

    private void applyGroupedExitToPositions(
            List<TradePosition> positions,
            FillProcessingResult result,
            TradeType tradeType
    ) {
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
            position.setStatus(STATUS_CLOSED);
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
            return true;
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

        if (isInvalidSlice(runnerQty, avgEntryPrice)) {
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

        static PreTradeValidationResult valid(
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
