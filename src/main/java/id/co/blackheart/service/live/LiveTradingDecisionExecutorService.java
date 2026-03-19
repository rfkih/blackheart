package id.co.blackheart.service.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.portfolio.PortfolioService;
import id.co.blackheart.util.TradeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static id.co.blackheart.service.strategy.TrendFollowingStrategyService.SIDE_LONG;
import static id.co.blackheart.service.strategy.TrendFollowingStrategyService.SIDE_SHORT;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradingDecisionExecutorService {

    private static final BigDecimal MIN_USDT_NOTIONAL = new BigDecimal("7");
    private static final BigDecimal MIN_BASE_ASSET_QTY = new BigDecimal("0.00008");

    private final TradesRepository tradesRepository;
    private final PortfolioService portfolioService;
    private final TradeUtil tradeUtil;

    public void execute(Trades activeTrade, StrategyContext context, StrategyDecision decision) throws JsonProcessingException {
        switch (decision.getDecisionType()) {
            case OPEN_LONG -> executeOpenLong(context, decision);
            case OPEN_SHORT -> executeOpenShort(context, decision);
            case CLOSE_LONG -> executeCloseLong(activeTrade, context, decision);
            case CLOSE_SHORT -> executeCloseShort(activeTrade, context, decision);
            case UPDATE_TRAILING_STOP -> executeUpdateTrailingStop(activeTrade, decision);
            case HOLD -> log.debug("No execution for HOLD");
        }
    }

    public void executeListenerClose(Users user,Trades activeTrade,String asset,ListenerDecision listenerDecision) throws JsonProcessingException {
        if (activeTrade == null) {
            log.warn("Listener close skipped because activeTrade is null");
            return;
        }

        if (listenerDecision == null || !listenerDecision.isTriggered()) {
            log.debug("Listener close skipped because decision is not triggered");
            return;
        }

        activeTrade.setExitReason(listenerDecision.getExitReason());
        tradesRepository.save(activeTrade);

        if (SIDE_LONG.equalsIgnoreCase(activeTrade.getSide())) {
            if ("BNC".equalsIgnoreCase(user.getExchange())) {
                tradeUtil.binanceCloseLongMarketOrder(user, activeTrade, asset);
                return;
            }

            log.warn("Unsupported exchange for LONG listener close: {}", user.getExchange());
            return;
        }

        if (SIDE_SHORT.equalsIgnoreCase(activeTrade.getSide())) {
            if ("BNC".equalsIgnoreCase(user.getExchange())) {
                tradeUtil.binanceCloseShortMarketOrder(user, activeTrade, asset);
                return;
            }

            log.warn("Unsupported exchange for SHORT listener close: {}", user.getExchange());
            return;
        }

        log.warn("Listener close skipped because trade side is unknown | tradeId={} side={}",
                activeTrade.getTradeId(), activeTrade.getSide());
    }

    private void executeOpenLong(StrategyContext context, StrategyDecision decision) throws JsonProcessingException {
        Users user = context.getUser();
        String asset = context.getAsset();

        Portfolio usdtPortfolio = portfolioService.updateAndGetAssetBalance("USDT", user);
        BigDecimal balance = usdtPortfolio.getBalance();
        BigDecimal tradeAmount = calculateLongTradeAmount(balance, user);

        if (balance.compareTo(tradeAmount) < 0) {
            log.info("Insufficient USDT balance for LONG entry. balance={} required={}", balance, tradeAmount);
            return;
        }

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceOpenLongMarketOrder(
                    user,
                    asset,
                    mapToTradeDecision(decision),
                    decision.getStrategyName() + "_" + decision.getStrategyInterval().toUpperCase(),
                    tradeAmount
            );
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
            tradeUtil.binanceOpenShortMarketOrder(
                    user,
                    asset,
                    mapToTradeDecision(decision),
                    decision.getStrategyName() + "_" + decision.getStrategyInterval().toUpperCase(),
                    tradeAmount,
                    decision.getStrategyInterval()
            );
            return;
        }

        log.warn("Unsupported exchange for SHORT entry: {}", user.getExchange());
    }

    private void executeCloseLong(Trades activeTrade, StrategyContext context, StrategyDecision decision) throws JsonProcessingException {
        Users user = context.getUser();

        if (activeTrade == null) {
            log.warn("CLOSE_LONG skipped because activeTrade is null");
            return;
        }

        activeTrade.setExitReason(decision.getExitReason());
        tradesRepository.save(activeTrade);

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceCloseLongMarketOrder(user, activeTrade, context.getAsset());
            return;
        }

        log.warn("Unsupported exchange for LONG close: {}", user.getExchange());
    }

    private void executeCloseShort(Trades activeTrade, StrategyContext context, StrategyDecision decision) throws JsonProcessingException {
        Users user = context.getUser();

        if (activeTrade == null) {
            log.warn("CLOSE_SHORT skipped because activeTrade is null");
            return;
        }

        activeTrade.setExitReason(decision.getExitReason());
        tradesRepository.save(activeTrade);

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceCloseShortMarketOrder(user, activeTrade, context.getAsset());
            return;
        }

        log.warn("Unsupported exchange for SHORT close: {}", user.getExchange());
    }

    private void executeUpdateTrailingStop(Trades activeTrade, StrategyDecision decision) {
        if (activeTrade == null) {
            log.warn("UPDATE_TRAILING_STOP skipped because activeTrade is null");
            return;
        }

        activeTrade.setTrailingStopPrice(decision.getTrailingStopPrice());
        activeTrade.setCurrentStopLossPrice(decision.getStopLossPrice());
        tradesRepository.save(activeTrade);

        log.info("Trailing stop updated | tradeId={} asset={} newStop={}",
                activeTrade.getTradeId(),
                activeTrade.getAsset(),
                decision.getStopLossPrice());
    }

    private id.co.blackheart.dto.TradeDecision mapToTradeDecision(StrategyDecision decision) {
        return id.co.blackheart.dto.TradeDecision.builder()
                .action("LONG".equalsIgnoreCase(decision.getSide()) ? "BUY" : "SELL")
                .positionSize(decision.getPositionSize())
                .stopLossPrice(decision.getStopLossPrice())
                .takeProfitPrice(decision.getTakeProfitPrice())
                .build();
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