package id.co.blackheart.service.live;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import id.co.blackheart.service.trade.TradeService;
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
    private static final BigDecimal MIN_BTC_NOTIONAL =  new BigDecimal("0.00001");

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_PARTIALLY_CLOSED = "PARTIALLY_CLOSED";

    private final TradesRepository tradesRepository;
    private final TradePositionRepository tradePositionRepository;
    private final PortfolioService portfolioService;
    private final TradeService tradeService;

    public void execute(Trades activeTrade, StrategyContext context,StrategyDecision decision) throws JsonProcessingException {
        if (decision == null || decision.getDecisionType() == null) {
            return;
        }

        switch (decision.getDecisionType()) {
            case OPEN_LONG -> executeOpenLong(context, decision);
            case OPEN_SHORT -> executeOpenShort(context, decision);
            case UPDATE_POSITION_MANAGEMENT -> executeUpdatePositionManagement(activeTrade, decision);
            case CLOSE_LONG  -> executeCloseTrade(activeTrade, context.getUser(), decision.getExitReason());
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
            tradeService.binanceCloseLongPositionMarketOrder(user, activeTradePosition, asset);
        } else if ("SHORT".equalsIgnoreCase(activeTradePosition.getSide())) {
            tradeService.binanceCloseShortPositionMarketOrder(user, activeTradePosition, asset);
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

    public void executeManualClosePosition(
            Users user,
            UUID tradePositionId
    ) throws JsonProcessingException {
        TradePosition tradePosition = tradePositionRepository.findByTradePositionId(tradePositionId).orElse(null);
        if (tradePosition == null || !STATUS_OPEN.equalsIgnoreCase(tradePosition.getStatus())) {
            return;
        }

        tradePosition.setExitReason("MANUAL_CLOSE");
        tradePositionRepository.save(tradePosition);

        if ("LONG".equalsIgnoreCase(tradePosition.getSide())) {
            tradeService.binanceCloseLongPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
        } else if ("SHORT".equalsIgnoreCase(tradePosition.getSide())) {
            tradeService.binanceCloseShortPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
        }

        refreshParentTradeSummary(tradePosition.getTradeId());
    }

    public void executeListenerClosePositions(
            Users user,
            List<TradePosition> activeTradePositions,
            String asset,
            ListenerDecision listenerDecision
    ) throws JsonProcessingException {
        if (activeTradePositions == null || activeTradePositions.isEmpty() || listenerDecision == null || !listenerDecision.isTriggered()) {
            return;
        }

        TradePosition firstPosition = activeTradePositions.get(0);

        for (TradePosition tradePosition : activeTradePositions) {
            tradePosition.setExitReason(listenerDecision.getExitReason());
        }
        tradePositionRepository.saveAll(activeTradePositions);

        if ("LONG".equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseLongPositionsMarketOrder(user, activeTradePositions, asset);
        } else if ("SHORT".equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseShortPositionsMarketOrder(user, activeTradePositions, asset);
        } else {
            log.warn("Unknown side for grouped listener close | tradeId={} side={}",
                    firstPosition.getTradeId(), firstPosition.getSide());
            return;
        }

        refreshParentTradeSummary(firstPosition.getTradeId());
    }

    public void executeManualCloseTrade(Users user,UUID tradeId) {
        List<TradePosition> openPositions = tradePositionRepository.findAllByTradeIdAndStatus(tradeId, STATUS_OPEN);

        for (TradePosition tradePosition : openPositions) {
            tradePosition.setExitReason("MANUAL_CLOSE");
            tradePositionRepository.save(tradePosition);

            if ("LONG".equalsIgnoreCase(tradePosition.getSide())) {
                tradeService.binanceCloseLongPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
            } else if ("SHORT".equalsIgnoreCase(tradePosition.getSide())) {
                tradeService.binanceCloseShortPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
            }
        }

        refreshParentTradeSummary(tradeId);
    }

    private void executeOpenLong(
            StrategyContext context,
            StrategyDecision decision
    ) throws JsonProcessingException {
        Users user = context.getUser();

        Portfolio usdtPortfolio = portfolioService.updateAndGetAssetBalance("USDT", user);
        BigDecimal balance = safe(usdtPortfolio.getBalance());
        BigDecimal tradeAmount = calculateLongTradeAmount(balance, user);

        if (balance.compareTo(tradeAmount) < 0) {
            log.info("Insufficient USDT balance for LONG entry. balance={} required={}", balance, tradeAmount);
            return;
        }

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeService.binanceOpenLongMarketOrder(context, decision, tradeAmount);
            return;
        }

        log.warn("Unsupported exchange for LONG entry: {}", user.getExchange());
    }

    private void executeOpenShort(
            StrategyContext context,
            StrategyDecision decision
    ) throws JsonProcessingException {
        Users user = context.getUser();

        Portfolio btcPortfolio = portfolioService.updateAndGetAssetBalance("BTC", user);
        BigDecimal balance = safe(btcPortfolio.getBalance());
        BigDecimal tradeAmount = calculateShortTradeAmount(balance, user);

        if (balance.compareTo(tradeAmount) < 0) {
            log.info("Insufficient USDT balance for SHORT entry. balance={} required={}", balance, tradeAmount);
            return;
        }

        log.info(
                "SHORT entry sizing | usdtBalance={} riskAmount={} tradeQuoteNotional={}",
                balance,
                user.getRiskAmount(),
                tradeAmount
        );

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeService.binanceOpenShortMarketOrder(context,decision, tradeAmount, context.getAsset());
            return;
        }

        log.warn("Unsupported exchange for SHORT entry: {}", user.getExchange());
    }

    private void executeUpdatePositionManagement(Trades activeTrade,StrategyDecision decision) {
        if (activeTrade == null || decision == null) {
            return;
        }

        tradeService.updateOpenTradePositions(activeTrade, decision);
        refreshParentTradeSummary(activeTrade.getTradeId());
    }

    private void executeCloseTrade(Trades activeTrade,Users user,String exitReason) {
        if (activeTrade == null || user == null) {
            return;
        }

        List<TradePosition> openPositions =
                tradePositionRepository.findAllByTradeIdAndStatus(activeTrade.getTradeId(), STATUS_OPEN);

        for (TradePosition tradePosition : openPositions) {
            tradePosition.setExitReason(exitReason != null ? exitReason : "STRATEGY_EXIT");
            tradePositionRepository.save(tradePosition);

            if ("LONG".equalsIgnoreCase(tradePosition.getSide())) {
                tradeService.binanceCloseLongPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
            } else if ("SHORT".equalsIgnoreCase(tradePosition.getSide())) {
                tradeService.binanceCloseShortPositionMarketOrder(user, tradePosition, tradePosition.getAsset());
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
                    .max(Comparator.comparing(
                            TradePosition::getExitTime,
                            Comparator.nullsLast(Comparator.naturalOrder())
                    ))
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

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal calculateLongTradeAmount(BigDecimal usdtBalance, Users user) {
        BigDecimal tradeAmount = usdtBalance
                .multiply(user.getRiskAmount())
                .setScale(8, RoundingMode.DOWN);

        return tradeAmount.compareTo(MIN_USDT_NOTIONAL) < 0 ? MIN_USDT_NOTIONAL : tradeAmount;
    }

    private BigDecimal calculateShortTradeAmount(BigDecimal btcBalance, Users user) {
        BigDecimal tradeAmount = btcBalance
                .multiply(user.getRiskAmount())
                .setScale(8, RoundingMode.DOWN);

        return tradeAmount.compareTo(MIN_BTC_NOTIONAL) < 0 ? MIN_BTC_NOTIONAL : tradeAmount;
    }
}