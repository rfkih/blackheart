package id.co.blackheart.service.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.service.portfolio.PortfolioService;
import id.co.blackheart.service.trade.TradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradingDecisionExecutorService {

    private static final BigDecimal MIN_USDT_NOTIONAL = new BigDecimal("7");
    private static final BigDecimal MIN_BTC_NOTIONAL = new BigDecimal("0.00001");

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_PARTIALLY_CLOSED = "PARTIALLY_CLOSED";

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";
    private static final String EXIT_REASON_MANUAL_CLOSE = "MANUAL_CLOSE";
    private static final String EXIT_REASON_STRATEGY_EXIT = "STRATEGY_EXIT";

    private final TradePositionRepository tradePositionRepository;
    private final PortfolioService portfolioService;
    private final TradeService tradeService;

    public void execute(
            Trades activeTrade,
            EnrichedStrategyContext context,
            StrategyDecision decision
    ) throws JsonProcessingException {
        if (decision == null || decision.getDecisionType() == null || context == null) {
            return;
        }

        switch (decision.getDecisionType()) {
            case OPEN_LONG -> executeOpenLong(context, decision);
            case OPEN_SHORT -> executeOpenShort(context, decision);
            case UPDATE_POSITION_MANAGEMENT -> executeUpdatePositionManagement(activeTrade, decision);
            case CLOSE_LONG -> executeCloseTrade(activeTrade, context.getAccount(), decision.getExitReason());
            case CLOSE_SHORT -> executeCloseTrade(activeTrade, context.getAccount(), decision.getExitReason());
            case HOLD -> log.debug("No execution for HOLD");
            default -> log.debug("Decision type not handled yet: {}", decision.getDecisionType());
        }
    }

    public void executeListenerClosePosition(
            Account account,
            TradePosition activeTradePosition,
            String asset,
            ListenerDecision listenerDecision
    ) throws JsonProcessingException {
        if (activeTradePosition == null || listenerDecision == null || !listenerDecision.isTriggered()) {
            return;
        }

        activeTradePosition.setExitReason(listenerDecision.getExitReason());
        tradePositionRepository.save(activeTradePosition);

        if (SIDE_LONG.equalsIgnoreCase(activeTradePosition.getSide())) {
            tradeService.binanceCloseLongPositionMarketOrder(account, activeTradePosition, asset);
        } else if (SIDE_SHORT.equalsIgnoreCase(activeTradePosition.getSide())) {
            tradeService.binanceCloseShortPositionMarketOrder(account, activeTradePosition, asset);
        } else {
            log.warn(
                    "Unknown side for listener close | tradePositionId={} side={}",
                    activeTradePosition.getTradePositionId(),
                    activeTradePosition.getSide()
            );
        }
    }

    public void executeManualClosePosition(Account account, UUID tradePositionId) throws JsonProcessingException {
        TradePosition tradePosition = tradePositionRepository.findByTradePositionId(tradePositionId).orElse(null);
        if (tradePosition == null || !STATUS_OPEN.equalsIgnoreCase(tradePosition.getStatus())) {
            return;
        }

        tradePosition.setExitReason(EXIT_REASON_MANUAL_CLOSE);
        tradePositionRepository.save(tradePosition);

        if (SIDE_LONG.equalsIgnoreCase(tradePosition.getSide())) {
            tradeService.binanceCloseLongPositionMarketOrder(account, tradePosition, tradePosition.getAsset());
        } else if (SIDE_SHORT.equalsIgnoreCase(tradePosition.getSide())) {
            tradeService.binanceCloseShortPositionMarketOrder(account, tradePosition, tradePosition.getAsset());
        } else {
            log.warn(
                    "Unknown side for manual close | tradePositionId={} side={}",
                    tradePosition.getTradePositionId(),
                    tradePosition.getSide()
            );
        }
    }

    public void executeListenerClosePositions(
            Account account,
            List<TradePosition> activeTradePositions,
            String asset,
            ListenerDecision listenerDecision
    ) throws JsonProcessingException {
        if (activeTradePositions == null || activeTradePositions.isEmpty()
                || listenerDecision == null || !listenerDecision.isTriggered()) {
            return;
        }

        TradePosition firstPosition = activeTradePositions.getFirst();

        for (TradePosition tradePosition : activeTradePositions) {
            tradePosition.setExitReason(listenerDecision.getExitReason());
        }
        tradePositionRepository.saveAll(activeTradePositions);

        if (SIDE_LONG.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseLongPositionsMarketOrder(account, activeTradePositions, asset);
        } else if (SIDE_SHORT.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseShortPositionsMarketOrder(account, activeTradePositions, asset);
        } else {
            log.warn(
                    "Unknown side for grouped listener close | tradeId={} side={}",
                    firstPosition.getTradeId(),
                    firstPosition.getSide()
            );
        }
    }

    public void executeManualCloseTrade(Account account, UUID tradeId) throws JsonProcessingException {
        List<TradePosition> openPositions = tradePositionRepository.findAllByTradeIdAndStatus(tradeId, STATUS_OPEN);
        if (openPositions.isEmpty()) {
            return;
        }

        TradePosition firstPosition = openPositions.getFirst();

        for (TradePosition tradePosition : openPositions) {
            tradePosition.setExitReason(EXIT_REASON_MANUAL_CLOSE);
        }
        tradePositionRepository.saveAll(openPositions);

        if (SIDE_LONG.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseLongPositionsMarketOrder(account, openPositions, firstPosition.getAsset());
        } else if (SIDE_SHORT.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseShortPositionsMarketOrder(account, openPositions, firstPosition.getAsset());
        } else {
            log.warn(
                    "Unknown side for manual trade close | tradeId={} side={}",
                    tradeId,
                    firstPosition.getSide()
            );
        }
    }

    private void executeOpenLong(
            EnrichedStrategyContext context,
            StrategyDecision decision
    ) throws JsonProcessingException {
        Account account = context.getAccount();

        Portfolio usdtPortfolio = portfolioService.updateAndGetAssetBalance("USDT", account);
        BigDecimal balance = usdtPortfolio == null ? BigDecimal.ZERO : safe(usdtPortfolio.getBalance());

        BigDecimal tradeAmount = decision.getNotionalSize() != null
                ? decision.getNotionalSize()
                : calculateLongTradeAmount(balance, account);

        if (tradeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Invalid LONG trade amount. tradeAmount={}", tradeAmount);
            return;
        }

        if (balance.compareTo(tradeAmount) < 0) {
            log.info(
                    "Insufficient USDT balance for LONG entry | balance={} required={}",
                    balance,
                    tradeAmount
            );
            return;
        }

        if ("BNC".equalsIgnoreCase(account.getExchange())) {
            tradeService.binanceOpenLongMarketOrder(context, decision, tradeAmount);
            return;
        }

        log.warn("Unsupported exchange for LONG entry: {}", account.getExchange());
    }

    private void executeOpenShort(
            EnrichedStrategyContext context,
            StrategyDecision decision
    ) throws JsonProcessingException {
        Account account = context.getAccount();

        Portfolio btcPortfolio = portfolioService.updateAndGetAssetBalance("BTC", account);
        BigDecimal balance = btcPortfolio == null ? BigDecimal.ZERO : safe(btcPortfolio.getBalance());

        BigDecimal tradeAmount = decision.getPositionSize() != null
                ? decision.getPositionSize()
                : calculateShortTradeAmount(balance, account);

        if (tradeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Invalid SHORT trade amount. tradeAmount={}", tradeAmount);
            return;
        }

        if (balance.compareTo(tradeAmount) < 0) {
            log.info(
                    "Insufficient BTC balance for SHORT entry | balance={} required={}",
                    balance,
                    tradeAmount
            );
            return;
        }

        log.info(
                "SHORT entry sizing | btcBalance={} riskAmount={} tradeBaseQty={}",
                balance,
                account.getRiskAmount(),
                tradeAmount
        );

        if ("BNC".equalsIgnoreCase(account.getExchange())) {
            tradeService.binanceOpenShortMarketOrder(context, decision, tradeAmount, context.getAsset());
            return;
        }

        log.warn("Unsupported exchange for SHORT entry: {}", account.getExchange());
    }

    private void executeUpdatePositionManagement(Trades activeTrade, StrategyDecision decision) {
        if (activeTrade == null || decision == null) {
            return;
        }

        tradeService.updateOpenTradePositions(activeTrade, decision);
    }

    private void executeCloseTrade(Trades activeTrade, Account account, String exitReason) throws JsonProcessingException {
        if (activeTrade == null || account == null) {
            return;
        }

        List<TradePosition> openPositions =
                tradePositionRepository.findAllByTradeIdAndStatus(activeTrade.getTradeId(), STATUS_OPEN);

        if (openPositions.isEmpty()) {
            return;
        }

        TradePosition firstPosition = openPositions.getFirst();

        for (TradePosition tradePosition : openPositions) {
            tradePosition.setExitReason(exitReason != null ? exitReason : EXIT_REASON_STRATEGY_EXIT);
        }
        tradePositionRepository.saveAll(openPositions);

        if (SIDE_LONG.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseLongPositionsMarketOrder(account, openPositions, firstPosition.getAsset());
        } else if (SIDE_SHORT.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseShortPositionsMarketOrder(account, openPositions, firstPosition.getAsset());
        } else {
            log.warn(
                    "Unknown side for close trade | tradeId={} side={}",
                    activeTrade.getTradeId(),
                    firstPosition.getSide()
            );
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal calculateLongTradeAmount(BigDecimal usdtBalance, Account account) {
        if (usdtBalance == null || account == null || account.getRiskAmount() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal tradeAmount = usdtBalance
                .multiply(account.getRiskAmount())
                .setScale(8, RoundingMode.DOWN);

        return tradeAmount.compareTo(MIN_USDT_NOTIONAL) < 0 ? MIN_USDT_NOTIONAL : tradeAmount;
    }

    private BigDecimal calculateShortTradeAmount(BigDecimal btcBalance, Account account) {
        if (btcBalance == null || account == null || account.getRiskAmount() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal tradeAmount = btcBalance
                .multiply(account.getRiskAmount())
                .setScale(8, RoundingMode.DOWN);

        return tradeAmount.compareTo(MIN_BTC_NOTIONAL) < 0 ? MIN_BTC_NOTIONAL : tradeAmount;
    }
}