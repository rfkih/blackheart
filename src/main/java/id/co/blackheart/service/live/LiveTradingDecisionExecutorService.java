package id.co.blackheart.service.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import id.co.blackheart.dto.TradeDecision;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.portfolio.PortfolioService;
import id.co.blackheart.util.TradeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradingDecisionExecutorService {

    private static final BigDecimal MIN_USDT_NOTIONAL = new BigDecimal("7");
    private static final BigDecimal MIN_BASE_ASSET_QTY = new BigDecimal("0.00008");

    private final TradesRepository tradesRepository;
    private final TradePositionRepository tradePositionRepository;
    private final PortfolioService portfolioService;
    private final TradeUtil tradeUtil;

    public void execute(Trades activeTrade, StrategyContext context, StrategyDecision decision) throws JsonProcessingException {
        if (decision == null || decision.getDecisionType() == null) {
            return;
        }

        switch (decision.getDecisionType()) {
            case OPEN_LONG -> executeOpenLong(context, decision);
            case OPEN_SHORT -> executeOpenShort(context, decision);
            case UPDATE_TRAILING_STOP -> executeUpdateTrailingStop(activeTrade, decision);
            case CLOSE_LONG -> executeCloseTrade(activeTrade, context.getUser(), decision.getExitReason());
            case CLOSE_SHORT -> executeCloseTrade(activeTrade, context.getUser(), decision.getExitReason());
            case HOLD -> log.debug("No execution for HOLD");
            default -> log.debug("Decision type not handled yet: {}", decision.getDecisionType());
        }
    }

    public void executeListenerClosePosition(
            Users user,
            TradePosition activeTradePosition,
            String asset,
            ListenerDecision listenerDecision
    ) throws JsonProcessingException {
        if (activeTradePosition == null || listenerDecision == null || !listenerDecision.isTriggered()) {
            return;
        }

        activeTradePosition.setExitReason(listenerDecision.getExitReason());
        tradePositionRepository.save(activeTradePosition);

        if ("LONG".equalsIgnoreCase(activeTradePosition.getSide())) {
            tradeUtil.binanceCloseLongPositionMarketOrder(user, activeTradePosition, asset);
        } else if ("SHORT".equalsIgnoreCase(activeTradePosition.getSide())) {
            tradeUtil.binanceCloseShortPositionMarketOrder(user, activeTradePosition, asset);
        } else {
            log.warn(
                    "Unknown side for listener close | tradePositionId={} side={}",
                    activeTradePosition.getTradePositionId(),
                    activeTradePosition.getSide()
            );
            return;
        }

        refreshParentTradeSummary(activeTradePosition.getTradeId());
    }

    public void executeManualClosePosition(Users user, UUID tradePositionId) throws JsonProcessingException {
        TradePosition tradePosition = tradePositionRepository.findByTradePositionId(tradePositionId).orElse(null);
        if (tradePosition == null || !"OPEN".equalsIgnoreCase(tradePosition.getStatus())) {
            return;
        }

        tradePosition.setExitReason("MANUAL_CLOSE");
        tradePositionRepository.save(tradePosition);

        if ("LONG".equalsIgnoreCase(tradePosition.getSide())) {
            tradeUtil.binanceCloseLongPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
        } else if ("SHORT".equalsIgnoreCase(tradePosition.getSide())) {
            tradeUtil.binanceCloseShortPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
        }

        refreshParentTradeSummary(tradePosition.getTradeId());
    }

    public void executeManualCloseTrade(Users user, UUID tradeId) throws JsonProcessingException {
        List<TradePosition> openPositions = tradePositionRepository.findAllByTradeIdAndStatus(tradeId, "OPEN");

        for (TradePosition tradePosition : openPositions) {
            tradePosition.setExitReason("MANUAL_CLOSE");
            tradePositionRepository.save(tradePosition);

            if ("LONG".equalsIgnoreCase(tradePosition.getSide())) {
                tradeUtil.binanceCloseLongPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
            } else if ("SHORT".equalsIgnoreCase(tradePosition.getSide())) {
                tradeUtil.binanceCloseShortPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
            }
        }

        refreshParentTradeSummary(tradeId);
    }

    private void executeOpenLong(StrategyContext context, StrategyDecision decision) throws JsonProcessingException {
        Users user = context.getUser();

        Portfolio usdtPortfolio = portfolioService.updateAndGetAssetBalance("USDT", user);
        BigDecimal balance = usdtPortfolio.getBalance();
        BigDecimal tradeAmount = calculateLongTradeAmount(balance, user);

        if (balance.compareTo(tradeAmount) < 0) {
            log.info("Insufficient USDT balance for LONG entry. balance={} required={}", balance, tradeAmount);
            return;
        }

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceOpenLongMarketOrder(context, decision, tradeAmount);
            return;
        }

        log.warn("Unsupported exchange for LONG entry: {}", user.getExchange());
    }

    private void executeOpenShort(StrategyContext context, StrategyDecision decision) throws JsonProcessingException {
        Users user = context.getUser();
        String asset = context.getAsset();
        String baseAsset = resolveBaseAsset(asset);

        Portfolio basePortfolio = portfolioService.updateAndGetAssetBalance(baseAsset, user);
        BigDecimal balance = basePortfolio.getBalance();
        BigDecimal tradeAmount = calculateShortTradeAmount(balance, user);

        if (balance.compareTo(tradeAmount) < 0) {
            log.info("Insufficient {} balance for SHORT entry. balance={} required={}", baseAsset, balance, tradeAmount);
            return;
        }

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceOpenShortMarketOrder(context, asset, decision, tradeAmount);
            return;
        }

        log.warn("Unsupported exchange for SHORT entry: {}", user.getExchange());
    }

    private void executeUpdateTrailingStop(Trades activeTrade, StrategyDecision decision) {
        if (activeTrade == null) {
            return;
        }

        List<TradePosition> openPositions =
                tradePositionRepository.findAllByTradeIdAndStatus(activeTrade.getTradeId(), "OPEN");

        for (TradePosition tradePosition : openPositions) {
            if (!"RUNNER".equalsIgnoreCase(tradePosition.getPositionRole())
                    && !"SINGLE".equalsIgnoreCase(tradePosition.getPositionRole())) {
                continue;
            }

            if (decision.getTrailingStopPrice() != null) {
                tradePosition.setTrailingStopPrice(decision.getTrailingStopPrice());
            }

            if (decision.getStopLossPrice() != null) {
                tradePosition.setCurrentStopLossPrice(decision.getStopLossPrice());
            }

            if (decision.getTakeProfitPrice() != null) {
                tradePosition.setTakeProfitPrice(decision.getTakeProfitPrice());
            }

            tradePositionRepository.save(tradePosition);
        }
    }

    private void executeCloseTrade(Trades activeTrade, Users user, String exitReason) throws JsonProcessingException {
        if (activeTrade == null || user == null) {
            return;
        }

        List<TradePosition> openPositions =
                tradePositionRepository.findAllByTradeIdAndStatus(activeTrade.getTradeId(), "OPEN");

        for (TradePosition tradePosition : openPositions) {
            tradePosition.setExitReason(exitReason != null ? exitReason : "STRATEGY_EXIT");
            tradePositionRepository.save(tradePosition);

            if ("LONG".equalsIgnoreCase(tradePosition.getSide())) {
                tradeUtil.binanceCloseLongPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
            } else if ("SHORT".equalsIgnoreCase(tradePosition.getSide())) {
                tradeUtil.binanceCloseShortPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
            }
        }

        refreshParentTradeSummary(activeTrade.getTradeId());
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
                .filter(tp -> "CLOSED".equalsIgnoreCase(tp.getStatus()))
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
                .filter(tp -> "OPEN".equalsIgnoreCase(tp.getStatus()))
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
            trade.setStatus("CLOSED");
            trade.setExitTime(LocalDateTime.now());

            TradePosition latestClosed = closedPositions.stream()
                    .max(Comparator.comparing(
                            TradePosition::getExitTime,
                            Comparator.nullsLast(Comparator.naturalOrder())
                    ))
                    .orElse(null);

            if (latestClosed != null) {
                trade.setExitReason(latestClosed.getExitReason());
            }
        } else if (openCount < allPositions.size()) {
            trade.setStatus("PARTIALLY_CLOSED");
        } else {
            trade.setStatus("OPEN");
        }

        tradesRepository.save(trade);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }


    private BigDecimal calculateLongTradeAmount(BigDecimal usdtBalance, Users user) {
        BigDecimal tradeAmount = usdtBalance
                .multiply(user.getRiskAmount())
                .setScale(8, RoundingMode.DOWN);

        return tradeAmount.compareTo(MIN_USDT_NOTIONAL) < 0 ? MIN_USDT_NOTIONAL : tradeAmount;
    }

    private BigDecimal calculateShortTradeAmount(BigDecimal baseAssetBalance, Users user) {
        BigDecimal tradeAmount = baseAssetBalance
                .multiply(user.getRiskAmount())
                .setScale(8, RoundingMode.DOWN);

        return tradeAmount.compareTo(MIN_BASE_ASSET_QTY) < 0 ? MIN_BASE_ASSET_QTY : tradeAmount;
    }

    private String resolveBaseAsset(String asset) {
        if (asset != null && asset.endsWith("USDT")) {
            return asset.substring(0, asset.length() - 4);
        }
        return asset;
    }
}