package id.co.blackheart.service.trade;

import id.co.blackheart.dto.request.BinanceOrderRequest;
import id.co.blackheart.dto.response.BinanceOrderFill;
import id.co.blackheart.dto.response.BinanceOrderResponse;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.TradePositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import static id.co.blackheart.util.TradeConstant.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeCloseService {

    private final TradePositionRepository tradePositionRepository;
    private final TradeExecutionService tradeExecutionService;
    private final TradeSummaryService tradeSummaryService;
    private final TradeStateSyncService tradeStateSyncService;

    public void closeSinglePosition(
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

            tradeSummaryService.refreshParentTradeSummary(validated.getTradeId());
            tradeStateSyncService.syncTradeState(validated.getTradeId());

        } catch (Exception e) {
            log.error("❌ Error closing {} position | tradePositionId={}", tradeType, tradePosition.getTradePositionId(), e);
        }
    }

    public void closeGroupedPositions(
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
                return;
            }

            BigDecimal requestAmount = buildGroupedCloseRequestAmount(validOpenPositions, closeQty, tradeType, asset);

            BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(
                    buildOrderRequest(asset, resolveCloseOrderSide(tradeType), requestAmount, user)
            );

            FillProcessingResult result = processOrderFills(response);

            applyGroupedExitToPositions(validOpenPositions, result, tradeType);
            tradePositionRepository.saveAll(validOpenPositions);

            tradeSummaryService.refreshParentTradeSummary(first.getTradeId());
            tradeStateSyncService.syncTradeState(first.getTradeId());

        } catch (Exception e) {
            log.error("❌ Error closing grouped {} positions | tradeId={}", tradeType, first.getTradeId(), e);
        }
    }

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
        tradeStateSyncService.syncTradeState(activeTrade.getTradeId());
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

    private BigDecimal safe(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

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
}