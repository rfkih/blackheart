package id.co.blackheart.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import id.co.blackheart.dto.TradeDecision;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.util.TradeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrendFollowingStrategyService {

    private static final String STRATEGY_NAME = "TREND_FOLLOWING_4H";
    private static final String STRATEGY_INTERVAL = "4h";

    private static final BigDecimal MIN_ADX = new BigDecimal("20");
    private static final BigDecimal MIN_EFFICIENCY_RATIO = new BigDecimal("0.30");
    private static final BigDecimal MIN_RELATIVE_VOLUME = new BigDecimal("0.80");

    private static final BigDecimal STOP_ATR_MULTIPLIER = new BigDecimal("1.8");
    private static final BigDecimal TAKE_PROFIT_ATR_MULTIPLIER = new BigDecimal("3.0");
    private static final BigDecimal TRAILING_ATR_MULTIPLIER = new BigDecimal("2.0");

    private static final BigDecimal MIN_USDT_NOTIONAL = new BigDecimal("7");
    private static final BigDecimal MIN_BASE_ASSET_QTY = new BigDecimal("0.00008");

    private final TradesRepository tradesRepository;
    private final TradeUtil tradeUtil;
    private final PortfolioService portfolioService;

    public void execute(MarketData marketData,FeatureStore featureStore,Users user, String asset) throws JsonProcessingException {

        if (marketData == null || featureStore == null || user == null) {
            log.warn("Trend following skipped because marketData/featureStore/user is null.");
            return;
        }

        if (!STRATEGY_INTERVAL.equalsIgnoreCase(featureStore.getInterval())) {
            log.debug("Trend following skipped because interval is not {}. interval={}",
                    STRATEGY_INTERVAL, featureStore.getInterval());
            return;
        }

        Optional<Trades> activeTradeOpt = tradesRepository.findLatestOpenTrade(user.getId(),asset,STRATEGY_NAME,STRATEGY_INTERVAL);

        if (activeTradeOpt.isPresent()) {
            manageOpenTrade(user, asset, marketData, featureStore, activeTradeOpt.get());
            return;
        }

        openNewTradeIfEligible(user, asset, marketData, featureStore);
    }

    private void openNewTradeIfEligible(Users user,
                                        String asset,
                                        MarketData marketData,
                                        FeatureStore featureStore) throws JsonProcessingException {

        BigDecimal closePrice = marketData.getClosePrice();

        if (shouldOpenLong(featureStore, closePrice)) {
            TradeDecision decision = buildLongDecision(closePrice, featureStore);
            executeLongEntry(user, asset, decision, featureStore);
            return;
        }

        if (shouldOpenShort(featureStore, closePrice)) {
            TradeDecision decision = buildShortDecision(closePrice, featureStore);
            executeShortEntry(user, asset, decision, featureStore);
            return;
        }

        log.info("⏳ HOLD | strategy={} asset={} price={} regime={} bias={}",
                STRATEGY_NAME,
                asset,
                closePrice,
                featureStore.getTrendRegime(),
                featureStore.getEntryBias());
    }

    private void manageOpenTrade(Users user,String asset, MarketData marketData,FeatureStore featureStore,Trades activeTrade) throws JsonProcessingException {

        BigDecimal closePrice = marketData.getClosePrice();

        updateTrailingStop(activeTrade, closePrice, featureStore);

        if ("LONG".equalsIgnoreCase(activeTrade.getSide()) && shouldCloseLong(activeTrade, marketData, featureStore)) {
            closeLongTrade(user, asset, marketData, activeTrade, featureStore);
            return;
        }

        if ("SHORT".equalsIgnoreCase(activeTrade.getSide()) && shouldCloseShort(activeTrade, marketData, featureStore)) {
            closeShortTrade(user, asset, marketData, activeTrade, featureStore);
            return;
        }

        log.info("Open trade remains valid | strategy={} asset={} side={} close={} stop={} takeProfit={}",
                STRATEGY_NAME,
                asset,
                activeTrade.getSide(),
                closePrice,
                activeTrade.getCurrentStopLossPrice(),
                activeTrade.getTakeProfitPrice());
    }

    private boolean shouldOpenLong(FeatureStore featureStore, BigDecimal closePrice) {
        return isBullishRegime(featureStore, closePrice)
                && hasStrongTrend(featureStore)
                && hasBullishMomentum(featureStore)
                && hasAcceptableVolume(featureStore)
                && isValidLongTrigger(featureStore, closePrice);
    }

    private boolean shouldOpenShort(FeatureStore featureStore, BigDecimal closePrice) {
        return isBearishRegime(featureStore, closePrice)
                && hasStrongTrend(featureStore)
                && hasBearishMomentum(featureStore)
                && hasAcceptableVolume(featureStore)
                && isValidShortTrigger(featureStore, closePrice);
    }

    private boolean shouldCloseLong(Trades trade, MarketData marketData, FeatureStore featureStore) {
        BigDecimal closePrice = marketData.getClosePrice();

        boolean stopLossHit = trade.getCurrentStopLossPrice() != null
                && closePrice.compareTo(trade.getCurrentStopLossPrice()) <= 0;

        boolean takeProfitHit = trade.getTakeProfitPrice() != null
                && closePrice.compareTo(trade.getTakeProfitPrice()) >= 0;

        boolean regimeBroken = !"BULL".equalsIgnoreCase(featureStore.getTrendRegime());

        boolean momentumBroken = featureStore.getMacdHistogram() != null
                && featureStore.getMacdHistogram().compareTo(BigDecimal.ZERO) < 0
                && featureStore.getEma50() != null
                && closePrice.compareTo(featureStore.getEma50()) < 0;

        return stopLossHit || takeProfitHit || regimeBroken || momentumBroken;
    }

    private boolean shouldCloseShort(Trades trade, MarketData marketData, FeatureStore featureStore) {
        BigDecimal closePrice = marketData.getClosePrice();

        boolean stopLossHit = trade.getCurrentStopLossPrice() != null
                && closePrice.compareTo(trade.getCurrentStopLossPrice()) >= 0;

        boolean takeProfitHit = trade.getTakeProfitPrice() != null
                && closePrice.compareTo(trade.getTakeProfitPrice()) <= 0;

        boolean regimeBroken = !"BEAR".equalsIgnoreCase(featureStore.getTrendRegime());

        boolean momentumBroken = featureStore.getMacdHistogram() != null
                && featureStore.getMacdHistogram().compareTo(BigDecimal.ZERO) > 0
                && featureStore.getEma50() != null
                && closePrice.compareTo(featureStore.getEma50()) > 0;

        return stopLossHit || takeProfitHit || regimeBroken || momentumBroken;
    }

    private void updateTrailingStop(Trades activeTrade, BigDecimal closePrice, FeatureStore featureStore) {
        if (featureStore.getAtr() == null || featureStore.getAtr().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        BigDecimal trailingDistance = featureStore.getAtr().multiply(TRAILING_ATR_MULTIPLIER);

        if ("LONG".equalsIgnoreCase(activeTrade.getSide())) {
            BigDecimal candidateStop = closePrice.subtract(trailingDistance);

            if (activeTrade.getCurrentStopLossPrice() == null || candidateStop.compareTo(activeTrade.getCurrentStopLossPrice()) > 0) {

                activeTrade.setTrailingStopPrice(candidateStop);
                activeTrade.setCurrentStopLossPrice(candidateStop);
                tradesRepository.save(activeTrade);

                log.info("Updated LONG trailing stop | asset={} newStop={}", activeTrade.getAsset(), candidateStop);
            }
            return;
        }

        if ("SHORT".equalsIgnoreCase(activeTrade.getSide())) {BigDecimal candidateStop = closePrice.add(trailingDistance);

            if (activeTrade.getCurrentStopLossPrice() == null || candidateStop.compareTo(activeTrade.getCurrentStopLossPrice()) < 0) {

                activeTrade.setTrailingStopPrice(candidateStop);
                activeTrade.setCurrentStopLossPrice(candidateStop);
                tradesRepository.save(activeTrade);

                log.info("Updated SHORT trailing stop | asset={} newStop={}",
                        activeTrade.getAsset(), candidateStop);
            }
        }
    }

    private TradeDecision buildLongDecision(BigDecimal closePrice, FeatureStore featureStore) {
        BigDecimal atr = requireAtr(featureStore);

        BigDecimal stopLossPrice = closePrice.subtract(atr.multiply(STOP_ATR_MULTIPLIER));
        BigDecimal takeProfitPrice = closePrice.add(atr.multiply(TAKE_PROFIT_ATR_MULTIPLIER));

        return TradeDecision.builder()
                .action("BUY")
                .positionSize(BigDecimal.ONE)
                .stopLossPrice(stopLossPrice)
                .takeProfitPrice(takeProfitPrice)
                .build();
    }

    private TradeDecision buildShortDecision(BigDecimal closePrice, FeatureStore featureStore) {
        BigDecimal atr = requireAtr(featureStore);

        BigDecimal stopLossPrice = closePrice.add(atr.multiply(STOP_ATR_MULTIPLIER));
        BigDecimal takeProfitPrice = closePrice.subtract(atr.multiply(TAKE_PROFIT_ATR_MULTIPLIER));

        return TradeDecision.builder()
                .action("SELL")
                .positionSize(BigDecimal.ONE)
                .stopLossPrice(stopLossPrice)
                .takeProfitPrice(takeProfitPrice)
                .build();
    }

    private void executeLongEntry(Users user,
                                  String asset,
                                  TradeDecision decision,
                                  FeatureStore featureStore) throws JsonProcessingException {

        Portfolio usdtPortfolio = portfolioService.updateAndGetAssetBalance("USDT", user);
        BigDecimal tradeAmount = calculateLongTradeAmount(usdtPortfolio.getBalance(), user);

        if (usdtPortfolio.getBalance().compareTo(tradeAmount) < 0) {
            log.info("Insufficient USDT balance for LONG entry. balance={} required={}",
                    usdtPortfolio.getBalance(), tradeAmount);
            return;
        }

        if ("TKO".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.openLongMarketOrder(user, asset, decision, STRATEGY_NAME, tradeAmount);
            return;
        }

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceOpenLongMarketOrder(user, asset, decision, STRATEGY_NAME, tradeAmount);
            return;
        }

        log.warn("Unsupported exchange for LONG entry: {}", user.getExchange());
    }

    private void executeShortEntry(Users user,
                                   String asset,
                                   TradeDecision decision,
                                   FeatureStore featureStore) throws JsonProcessingException {

        String baseAsset = resolveBaseAsset(asset);
        Portfolio basePortfolio = portfolioService.updateAndGetAssetBalance(baseAsset, user);
        BigDecimal tradeAmount = calculateShortTradeAmount(basePortfolio.getBalance(), user);

        if (basePortfolio.getBalance().compareTo(tradeAmount) < 0) {
            log.info("Insufficient {} balance for SHORT entry. balance={} required={}",
                    baseAsset, basePortfolio.getBalance(), tradeAmount);
            return;
        }

        if ("TKO".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.openShortMarketOrder(user, asset, decision, STRATEGY_NAME, tradeAmount);
            return;
        }

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceOpenShortMarketOrder(user, asset, decision, STRATEGY_NAME, tradeAmount);
            return;
        }

        log.warn("Unsupported exchange for SHORT entry: {}", user.getExchange());
    }

    private void closeLongTrade(Users user,
                                String asset,
                                MarketData marketData,
                                Trades activeTrade,
                                FeatureStore featureStore) throws JsonProcessingException {

        markExitReason(activeTrade, marketData.getClosePrice(), featureStore);
        Optional<Trades> activeTradeOpt = Optional.of(activeTrade);

        if ("TKO".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.tokocryptoCloseLongMarketOrder(user, activeTradeOpt, marketData, asset);
            return;
        }

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceCloseLongMarketOrder(user, activeTradeOpt, marketData, asset);
            return;
        }

        log.warn("Unsupported exchange for LONG close: {}", user.getExchange());
    }

    private void closeShortTrade(Users user,
                                 String asset,
                                 MarketData marketData,
                                 Trades activeTrade,
                                 FeatureStore featureStore) throws JsonProcessingException {

        markExitReason(activeTrade, marketData.getClosePrice(), featureStore);
        Optional<Trades> activeTradeOpt = Optional.of(activeTrade);

        if ("TKO".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.tokocryptoCloseShortMarketOrder(user, activeTradeOpt, marketData, asset);
            return;
        }

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceCloseShortMarketOrder(user, activeTradeOpt, marketData, asset);
            return;
        }

        log.warn("Unsupported exchange for SHORT close: {}", user.getExchange());
    }

    private void markExitReason(Trades activeTrade, BigDecimal closePrice, FeatureStore featureStore) {
        if ("LONG".equalsIgnoreCase(activeTrade.getSide())) {
            if (activeTrade.getCurrentStopLossPrice() != null
                    && closePrice.compareTo(activeTrade.getCurrentStopLossPrice()) <= 0) {
                activeTrade.setExitReason("STOP_LOSS");
            } else if (activeTrade.getTakeProfitPrice() != null
                    && closePrice.compareTo(activeTrade.getTakeProfitPrice()) >= 0) {
                activeTrade.setExitReason("TAKE_PROFIT");
            } else {
                activeTrade.setExitReason("REGIME_REVERSAL");
            }
            tradesRepository.save(activeTrade);
            return;
        }

        if ("SHORT".equalsIgnoreCase(activeTrade.getSide())) {
            if (activeTrade.getCurrentStopLossPrice() != null
                    && closePrice.compareTo(activeTrade.getCurrentStopLossPrice()) >= 0) {
                activeTrade.setExitReason("STOP_LOSS");
            } else if (activeTrade.getTakeProfitPrice() != null
                    && closePrice.compareTo(activeTrade.getTakeProfitPrice()) <= 0) {
                activeTrade.setExitReason("TAKE_PROFIT");
            } else {
                activeTrade.setExitReason("REGIME_REVERSAL");
            }
            tradesRepository.save(activeTrade);
        }
    }

    private boolean isBullishRegime(FeatureStore f, BigDecimal closePrice) {
        return "BULL".equalsIgnoreCase(f.getTrendRegime())
                && f.getEma20() != null
                && f.getEma50() != null
                && f.getEma200() != null
                && f.getEma20().compareTo(f.getEma50()) > 0
                && f.getEma50().compareTo(f.getEma200()) > 0
                && closePrice.compareTo(f.getEma20()) >= 0
                && positiveOrZero(f.getEma50Slope())
                && positiveOrZero(f.getEma200Slope());
    }

    private boolean isBearishRegime(FeatureStore f, BigDecimal closePrice) {
        return "BEAR".equalsIgnoreCase(f.getTrendRegime())
                && f.getEma20() != null
                && f.getEma50() != null
                && f.getEma200() != null
                && f.getEma20().compareTo(f.getEma50()) < 0
                && f.getEma50().compareTo(f.getEma200()) < 0
                && closePrice.compareTo(f.getEma20()) <= 0
                && negativeOrZero(f.getEma50Slope())
                && negativeOrZero(f.getEma200Slope());
    }

    private boolean hasStrongTrend(FeatureStore f) {
        return f.getAdx() != null
                && f.getAdx().compareTo(MIN_ADX) >= 0
                && f.getEfficiencyRatio20() != null
                && f.getEfficiencyRatio20().compareTo(MIN_EFFICIENCY_RATIO) >= 0;
    }

    private boolean hasBullishMomentum(FeatureStore f) {
        return f.getPlusDI() != null
                && f.getMinusDI() != null
                && f.getPlusDI().compareTo(f.getMinusDI()) > 0
                && f.getMacd() != null
                && f.getMacdSignal() != null
                && f.getMacd().compareTo(f.getMacdSignal()) > 0
                && f.getMacdHistogram() != null
                && f.getMacdHistogram().compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean hasBearishMomentum(FeatureStore f) {
        return f.getPlusDI() != null
                && f.getMinusDI() != null
                && f.getPlusDI().compareTo(f.getMinusDI()) < 0
                && f.getMacd() != null
                && f.getMacdSignal() != null
                && f.getMacd().compareTo(f.getMacdSignal()) < 0
                && f.getMacdHistogram() != null
                && f.getMacdHistogram().compareTo(BigDecimal.ZERO) < 0;
    }

    private boolean hasAcceptableVolume(FeatureStore f) {
        return f.getRelativeVolume20() == null
                || f.getRelativeVolume20().compareTo(MIN_RELATIVE_VOLUME) >= 0;
    }

    private boolean isValidLongTrigger(FeatureStore f, BigDecimal closePrice) {
        boolean breakout = Boolean.TRUE.equals(f.getIsBreakout());
        boolean pullback = Boolean.TRUE.equals(f.getIsPullback())
                && f.getEma20() != null
                && closePrice.compareTo(f.getEma20()) >= 0;

        return breakout || pullback || "LONG".equalsIgnoreCase(f.getEntryBias());
    }

    private boolean isValidShortTrigger(FeatureStore f, BigDecimal closePrice) {
        boolean breakout = Boolean.TRUE.equals(f.getIsBreakout());
        boolean pullback = Boolean.TRUE.equals(f.getIsPullback())
                && f.getEma20() != null
                && closePrice.compareTo(f.getEma20()) <= 0;

        return breakout || pullback || "SHORT".equalsIgnoreCase(f.getEntryBias());
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

    private BigDecimal requireAtr(FeatureStore featureStore) {
        if (featureStore.getAtr() == null || featureStore.getAtr().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("ATR is required for trend following strategy.");
        }
        return featureStore.getAtr();
    }

    private boolean positiveOrZero(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0;
    }

    private boolean negativeOrZero(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) <= 0;
    }

    private String resolveBaseAsset(String asset) {
        if (asset != null && asset.endsWith("USDT")) {
            return asset.substring(0, asset.length() - 4);
        }
        return asset;
    }
}