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

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";

    private static final String EXIT_STOP_LOSS = "STOP_LOSS";
    private static final String EXIT_TAKE_PROFIT = "TAKE_PROFIT";
    private static final String EXIT_REGIME_REVERSAL = "REGIME_REVERSAL";
    private static final String EXIT_MOMENTUM_BREAKDOWN = "MOMENTUM_BREAKDOWN";

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

    public void execute(MarketData marketData, FeatureStore featureStore, Users user, String asset)
            throws JsonProcessingException {

        if (marketData == null || featureStore == null || user == null) {
            log.warn("Trend following skipped because marketData/featureStore/user is null.");
            return;
        }

        if (marketData.getClosePrice() == null) {
            log.warn("Trend following skipped because closePrice is null. asset={}", asset);
            return;
        }

        if (!STRATEGY_INTERVAL.equalsIgnoreCase(featureStore.getInterval())) {
            log.debug("Trend following skipped because interval is not {}. interval={}",
                    STRATEGY_INTERVAL, featureStore.getInterval());
            return;
        }

        Optional<Trades> activeTradeOpt = tradesRepository.findLatestOpenTrade(
                user.getId(), asset, STRATEGY_NAME, STRATEGY_INTERVAL
        );

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

        boolean bullishRegime = isBullishRegime(featureStore, closePrice);
        boolean strongTrend = hasStrongTrend(featureStore);
        boolean bullishMomentum = hasBullishMomentum(featureStore);
        boolean acceptableVolume = hasAcceptableVolume(featureStore);
        boolean validLongTrigger = isValidLongTrigger(featureStore);

        log.info(
                "Long entry check | asset={} strategy={} bullishRegime={} strongTrend={} bullishMomentum={} acceptableVolume={} validLongTrigger={}",
                asset, STRATEGY_NAME, bullishRegime, strongTrend, bullishMomentum, acceptableVolume, validLongTrigger
        );

        if (bullishRegime && strongTrend && bullishMomentum && acceptableVolume && validLongTrigger) {
            Optional<TradeDecision> decisionOpt = buildLongDecision(closePrice, featureStore);
            if (decisionOpt.isEmpty()) {
                log.warn("LONG skipped because ATR is invalid. asset={} closePrice={}", asset, closePrice);
                return;
            }

            executeLongEntry(user, asset, decisionOpt.get());
            return;
        }

        boolean bearishRegime = isBearishRegime(featureStore, closePrice);
        boolean bearishMomentum = hasBearishMomentum(featureStore);
        boolean validShortTrigger = isValidShortTrigger(featureStore);

        log.info(
                "Short entry check | asset={} strategy={} bearishRegime={} strongTrend={} bearishMomentum={} acceptableVolume={} validShortTrigger={}",
                asset, STRATEGY_NAME, bearishRegime, strongTrend, bearishMomentum, acceptableVolume, validShortTrigger
        );

        if (bearishRegime && strongTrend && bearishMomentum && acceptableVolume && validShortTrigger) {
            Optional<TradeDecision> decisionOpt = buildShortDecision(closePrice, featureStore);
            if (decisionOpt.isEmpty()) {
                log.warn("SHORT skipped because ATR is invalid. asset={} closePrice={}", asset, closePrice);
                return;
            }

            executeShortEntry(user, asset, decisionOpt.get());
            return;
        }

        log.info("⏳ HOLD | strategy={} asset={} price={} regime={} bias={}",STRATEGY_NAME,asset,closePrice,featureStore.getTrendRegime(),featureStore.getEntryBias());
    }

    private void manageOpenTrade(Users user,String asset,MarketData marketData,FeatureStore featureStore,Trades activeTrade) throws JsonProcessingException {

        BigDecimal closePrice = marketData.getClosePrice();

        if (SIDE_LONG.equalsIgnoreCase(activeTrade.getSide())) {
            String exitReason = getLongExitReason(activeTrade, marketData, featureStore);
            if (exitReason != null) {
                closeLongTrade(user, asset, marketData, activeTrade, exitReason);
                return;
            }

            updateTrailingStop(activeTrade, closePrice, featureStore);
            log.info("Open LONG remains valid | strategy={} asset={} close={} stop={} takeProfit={}",
                    STRATEGY_NAME,
                    asset,
                    closePrice,
                    activeTrade.getCurrentStopLossPrice(),
                    activeTrade.getTakeProfitPrice());
            return;
        }

        if (SIDE_SHORT.equalsIgnoreCase(activeTrade.getSide())) {
            String exitReason = getShortExitReason(activeTrade, marketData, featureStore);
            if (exitReason != null) {
                closeShortTrade(user, asset, marketData, activeTrade, exitReason);
                return;
            }

            updateTrailingStop(activeTrade, closePrice, featureStore);
            log.info("Open SHORT remains valid | strategy={} asset={} close={} stop={} takeProfit={}",
                    STRATEGY_NAME,
                    asset,
                    closePrice,
                    activeTrade.getCurrentStopLossPrice(),
                    activeTrade.getTakeProfitPrice());
            return;
        }

        log.warn("Unknown trade side found. asset={} side={}", asset, activeTrade.getSide());
    }

    private String getLongExitReason(Trades trade, MarketData marketData, FeatureStore featureStore) {
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

        log.info(
                "Long exit check | asset={} stopLossHit={} takeProfitHit={} regimeBroken={} momentumBroken={} closePrice={} stopLoss={} takeProfit={} trendRegime={} macdHistogram={} ema50={}",
                trade.getAsset(),
                stopLossHit,
                takeProfitHit,
                regimeBroken,
                momentumBroken,
                closePrice,
                trade.getCurrentStopLossPrice(),
                trade.getTakeProfitPrice(),
                featureStore.getTrendRegime(),
                featureStore.getMacdHistogram(),
                featureStore.getEma50()
        );

        if (stopLossHit) {
            return EXIT_STOP_LOSS;
        }
        if (takeProfitHit) {
            return EXIT_TAKE_PROFIT;
        }
        if (regimeBroken) {
            return EXIT_REGIME_REVERSAL;
        }
        if (momentumBroken) {
            return EXIT_MOMENTUM_BREAKDOWN;
        }
        return null;
    }

    private String getShortExitReason(Trades trade, MarketData marketData, FeatureStore featureStore) {
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

        log.info(
                "Short exit check | asset={} stopLossHit={} takeProfitHit={} regimeBroken={} momentumBroken={} closePrice={} stopLoss={} takeProfit={} trendRegime={} macdHistogram={} ema50={}",
                trade.getAsset(),
                stopLossHit,
                takeProfitHit,
                regimeBroken,
                momentumBroken,
                closePrice,
                trade.getCurrentStopLossPrice(),
                trade.getTakeProfitPrice(),
                featureStore.getTrendRegime(),
                featureStore.getMacdHistogram(),
                featureStore.getEma50()
        );

        if (stopLossHit) {
            return EXIT_STOP_LOSS;
        }
        if (takeProfitHit) {
            return EXIT_TAKE_PROFIT;
        }
        if (regimeBroken) {
            return EXIT_REGIME_REVERSAL;
        }
        if (momentumBroken) {
            return EXIT_MOMENTUM_BREAKDOWN;
        }
        return null;
    }

    private void updateTrailingStop(Trades activeTrade, BigDecimal closePrice, FeatureStore featureStore) {
        if (featureStore.getAtr() == null || featureStore.getAtr().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Trailing stop skipped because ATR is null/invalid. asset={}", activeTrade.getAsset());
            return;
        }

        BigDecimal trailingDistance = featureStore.getAtr().multiply(TRAILING_ATR_MULTIPLIER);

        if (SIDE_LONG.equalsIgnoreCase(activeTrade.getSide())) {
            BigDecimal candidateStop = closePrice.subtract(trailingDistance);

            if (activeTrade.getCurrentStopLossPrice() == null
                    || candidateStop.compareTo(activeTrade.getCurrentStopLossPrice()) > 0) {

                activeTrade.setTrailingStopPrice(candidateStop);
                activeTrade.setCurrentStopLossPrice(candidateStop);
                tradesRepository.save(activeTrade);

                log.info("Updated LONG trailing stop | asset={} oldStop={} newStop={} closePrice={} trailingDistance={}",
                        activeTrade.getAsset(),
                        activeTrade.getCurrentStopLossPrice(),
                        candidateStop,
                        closePrice,
                        trailingDistance);
            }
            return;
        }

        if (SIDE_SHORT.equalsIgnoreCase(activeTrade.getSide())) {
            BigDecimal candidateStop = closePrice.add(trailingDistance);

            if (activeTrade.getCurrentStopLossPrice() == null
                    || candidateStop.compareTo(activeTrade.getCurrentStopLossPrice()) < 0) {

                activeTrade.setTrailingStopPrice(candidateStop);
                activeTrade.setCurrentStopLossPrice(candidateStop);
                tradesRepository.save(activeTrade);

                log.info("Updated SHORT trailing stop | asset={} oldStop={} newStop={} closePrice={} trailingDistance={}",
                        activeTrade.getAsset(),
                        activeTrade.getCurrentStopLossPrice(),
                        candidateStop,
                        closePrice,
                        trailingDistance);
            }
        }
    }

    private Optional<TradeDecision> buildLongDecision(BigDecimal closePrice, FeatureStore featureStore) {
        Optional<BigDecimal> atrOpt = getValidAtr(featureStore);
        if (atrOpt.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal atr = atrOpt.get();
        BigDecimal stopLossPrice = closePrice.subtract(atr.multiply(STOP_ATR_MULTIPLIER));
        BigDecimal takeProfitPrice = closePrice.add(atr.multiply(TAKE_PROFIT_ATR_MULTIPLIER));

        return Optional.of(
                TradeDecision.builder()
                        .action("BUY")
                        .positionSize(BigDecimal.ONE)
                        .stopLossPrice(stopLossPrice)
                        .takeProfitPrice(takeProfitPrice)
                        .build()
        );
    }

    private Optional<TradeDecision> buildShortDecision(BigDecimal closePrice, FeatureStore featureStore) {
        Optional<BigDecimal> atrOpt = getValidAtr(featureStore);
        if (atrOpt.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal atr = atrOpt.get();
        BigDecimal stopLossPrice = closePrice.add(atr.multiply(STOP_ATR_MULTIPLIER));
        BigDecimal takeProfitPrice = closePrice.subtract(atr.multiply(TAKE_PROFIT_ATR_MULTIPLIER));

        return Optional.of(
                TradeDecision.builder()
                        .action("SELL")
                        .positionSize(BigDecimal.ONE)
                        .stopLossPrice(stopLossPrice)
                        .takeProfitPrice(takeProfitPrice)
                        .build()
        );
    }

    private void executeLongEntry(Users user,
                                  String asset,
                                  TradeDecision decision) throws JsonProcessingException {

        Portfolio usdtPortfolio = portfolioService.updateAndGetAssetBalance("USDT", user);
        BigDecimal balance = usdtPortfolio.getBalance();
        BigDecimal tradeAmount = calculateLongTradeAmount(balance, user);

        log.info("LONG sizing | asset={} exchange={} balanceUSDT={} riskAmount={} finalTradeAmount={} minNotional={}",
                asset, user.getExchange(), balance, user.getRiskAmount(), tradeAmount, MIN_USDT_NOTIONAL);

        if (balance.compareTo(tradeAmount) < 0) {
            log.info("Insufficient USDT balance for LONG entry. balance={} required={}",
                    balance, tradeAmount);
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
                                   TradeDecision decision) throws JsonProcessingException {

        String baseAsset = resolveBaseAsset(asset);
        Portfolio basePortfolio = portfolioService.updateAndGetAssetBalance(baseAsset, user);
        BigDecimal balance = basePortfolio.getBalance();
        BigDecimal tradeAmount = calculateShortTradeAmount(balance, user);

        log.info("SHORT sizing | asset={} baseAsset={} exchange={} baseBalance={} riskAmount={} finalTradeAmount={} minBaseQty={}",
                asset, baseAsset, user.getExchange(), balance, user.getRiskAmount(), tradeAmount, MIN_BASE_ASSET_QTY);

        if (balance.compareTo(tradeAmount) < 0) {
            log.info("Insufficient {} balance for SHORT entry. balance={} required={}",
                    baseAsset, balance, tradeAmount);
            return;
        }

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceOpenShortMarketOrder(user, asset, decision, STRATEGY_NAME, tradeAmount,"4h");
            return;
        }

        log.warn("Unsupported exchange for SHORT entry: {}", user.getExchange());
    }

    private void closeLongTrade(Users user,
                                String asset,
                                MarketData marketData,
                                Trades activeTrade,
                                String exitReason) throws JsonProcessingException {

        activeTrade.setExitReason(exitReason);
        tradesRepository.save(activeTrade);

        log.info("Closing LONG | asset={} exchange={} exitReason={} closePrice={}",
                asset, user.getExchange(), exitReason, marketData.getClosePrice());

        Optional<Trades> activeTradeOpt = Optional.of(activeTrade);


        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceCloseLongMarketOrder(user, activeTradeOpt.get(), asset);
            return;
        }

        log.warn("Unsupported exchange for LONG close: {}", user.getExchange());
    }

    private void closeShortTrade(Users user,
                                 String asset,
                                 MarketData marketData,
                                 Trades activeTrade,
                                 String exitReason) throws JsonProcessingException {

        activeTrade.setExitReason(exitReason);
        tradesRepository.save(activeTrade);

        log.info("Closing SHORT | asset={} exchange={} exitReason={} closePrice={}", asset, user.getExchange(), exitReason, marketData.getClosePrice());

        Optional<Trades> activeTradeOpt = Optional.of(activeTrade);

        if ("BNC".equalsIgnoreCase(user.getExchange())) {
            tradeUtil.binanceCloseShortMarketOrder(user, activeTradeOpt.get(), asset);
            return;
        }

        log.warn("Unsupported exchange for SHORT close: {}", user.getExchange());
    }

    private boolean isBullishRegime(FeatureStore f, BigDecimal closePrice) {
        boolean bullRegime = "BULL".equalsIgnoreCase(f.getTrendRegime());
        boolean ema20Exists = f.getEma20() != null;
        boolean ema50Exists = f.getEma50() != null;
        boolean ema200Exists = f.getEma200() != null;
        boolean ema20AboveEma50 = ema20Exists && ema50Exists && f.getEma20().compareTo(f.getEma50()) > 0;
        boolean ema50AboveEma200 = ema50Exists && ema200Exists && f.getEma50().compareTo(f.getEma200()) > 0;
        boolean priceAboveEma20 = ema20Exists && closePrice != null && closePrice.compareTo(f.getEma20()) >= 0;
        boolean ema50SlopePositive = positiveOrZero(f.getEma50Slope());
        boolean ema200SlopePositive = positiveOrZero(f.getEma200Slope());

        if (!bullRegime) {
            log.info("Bullish regime failed: trendRegime is not BULL, actual={}", f.getTrendRegime());
        }
        if (!ema20Exists) {
            log.info("Bullish regime failed: ema20 is null");
        }
        if (!ema50Exists) {
            log.info("Bullish regime failed: ema50 is null");
        }
        if (!ema200Exists) {
            log.info("Bullish regime failed: ema200 is null");
        }
        if (!ema20AboveEma50) {
            log.info("Bullish regime failed: ema20 <= ema50 | ema20={} ema50={}", f.getEma20(), f.getEma50());
        }
        if (!ema50AboveEma200) {
            log.info("Bullish regime failed: ema50 <= ema200 | ema50={} ema200={}", f.getEma50(), f.getEma200());
        }
        if (!priceAboveEma20) {
            log.info("Bullish regime failed: closePrice < ema20 | closePrice={} ema20={}", closePrice, f.getEma20());
        }
        if (!ema50SlopePositive) {
            log.info("Bullish regime failed: ema50Slope is negative/null | ema50Slope={}", f.getEma50Slope());
        }
        if (!ema200SlopePositive) {
            log.info("Bullish regime failed: ema200Slope is negative/null | ema200Slope={}", f.getEma200Slope());
        }

        return bullRegime
                && ema20Exists
                && ema50Exists
                && ema200Exists
                && ema20AboveEma50
                && ema50AboveEma200
                && priceAboveEma20
                && ema50SlopePositive
                && ema200SlopePositive;
    }

    private boolean isBearishRegime(FeatureStore f, BigDecimal closePrice) {
        boolean bearRegime = "BEAR".equalsIgnoreCase(f.getTrendRegime());
        boolean ema20Exists = f.getEma20() != null;
        boolean ema50Exists = f.getEma50() != null;
        boolean ema200Exists = f.getEma200() != null;
        boolean ema20BelowEma50 = ema20Exists && ema50Exists && f.getEma20().compareTo(f.getEma50()) < 0;
        boolean ema50BelowEma200 = ema50Exists && ema200Exists && f.getEma50().compareTo(f.getEma200()) < 0;
        boolean priceBelowEma20 = ema20Exists && closePrice != null && closePrice.compareTo(f.getEma20()) <= 0;
        boolean ema50SlopeNegative = negativeOrZero(f.getEma50Slope());
        boolean ema200SlopeNegative = negativeOrZero(f.getEma200Slope());

        if (!bearRegime) {
            log.info("Bearish regime failed: trendRegime is not BEAR, actual={}", f.getTrendRegime());
        }
        if (!ema20Exists) {
            log.info("Bearish regime failed: ema20 is null");
        }
        if (!ema50Exists) {
            log.info("Bearish regime failed: ema50 is null");
        }
        if (!ema200Exists) {
            log.info("Bearish regime failed: ema200 is null");
        }
        if (!ema20BelowEma50) {
            log.info("Bearish regime failed: ema20 >= ema50 | ema20={} ema50={}", f.getEma20(), f.getEma50());
        }
        if (!ema50BelowEma200) {
            log.info("Bearish regime failed: ema50 >= ema200 | ema50={} ema200={}", f.getEma50(), f.getEma200());
        }
        if (!priceBelowEma20) {
            log.info("Bearish regime failed: closePrice > ema20 | closePrice={} ema20={}", closePrice, f.getEma20());
        }
        if (!ema50SlopeNegative) {
            log.info("Bearish regime failed: ema50Slope is positive/null | ema50Slope={}", f.getEma50Slope());
        }
        if (!ema200SlopeNegative) {
            log.info("Bearish regime failed: ema200Slope is positive/null | ema200Slope={}", f.getEma200Slope());
        }

        return bearRegime
                && ema20Exists
                && ema50Exists
                && ema200Exists
                && ema20BelowEma50
                && ema50BelowEma200
                && priceBelowEma20
                && ema50SlopeNegative
                && ema200SlopeNegative;
    }

    private boolean hasStrongTrend(FeatureStore f) {
        boolean adxValid = f.getAdx() != null && f.getAdx().compareTo(MIN_ADX) >= 0;
        boolean efficiencyValid = f.getEfficiencyRatio20() != null
                && f.getEfficiencyRatio20().compareTo(MIN_EFFICIENCY_RATIO) >= 0;

        if (!adxValid) {
            log.info("Strong trend failed: adx is null/below minimum | adx={} minimum={}", f.getAdx(), MIN_ADX);
        }
        if (!efficiencyValid) {
            log.info("Strong trend failed: efficiencyRatio20 is null/below minimum | efficiencyRatio20={} minimum={}",
                    f.getEfficiencyRatio20(), MIN_EFFICIENCY_RATIO);
        }

        return adxValid && efficiencyValid;
    }

    private boolean hasBullishMomentum(FeatureStore f) {
        boolean plusDIExists = f.getPlusDI() != null;
        boolean minusDIExists = f.getMinusDI() != null;
        boolean plusDiAboveMinusDi = plusDIExists && minusDIExists && f.getPlusDI().compareTo(f.getMinusDI()) > 0;

        boolean macdExists = f.getMacd() != null;
        boolean macdSignalExists = f.getMacdSignal() != null;
        boolean macdAboveSignal = macdExists && macdSignalExists && f.getMacd().compareTo(f.getMacdSignal()) > 0;

        boolean histogramPositive = f.getMacdHistogram() != null
                && f.getMacdHistogram().compareTo(BigDecimal.ZERO) > 0;

        if (!plusDiAboveMinusDi) {
            log.info("Bullish momentum failed: plusDI <= minusDI | plusDI={} minusDI={}", f.getPlusDI(), f.getMinusDI());
        }
        if (!macdAboveSignal) {
            log.info("Bullish momentum failed: macd <= macdSignal | macd={} macdSignal={}", f.getMacd(), f.getMacdSignal());
        }
        if (!histogramPositive) {
            log.info("Bullish momentum failed: macdHistogram <= 0/null | macdHistogram={}", f.getMacdHistogram());
        }

        return plusDiAboveMinusDi && macdAboveSignal && histogramPositive;
    }

    private boolean hasBearishMomentum(FeatureStore f) {
        boolean plusDIExists = f.getPlusDI() != null;
        boolean minusDIExists = f.getMinusDI() != null;
        boolean plusDiBelowMinusDi = plusDIExists && minusDIExists && f.getPlusDI().compareTo(f.getMinusDI()) < 0;

        boolean macdExists = f.getMacd() != null;
        boolean macdSignalExists = f.getMacdSignal() != null;
        boolean macdBelowSignal = macdExists && macdSignalExists && f.getMacd().compareTo(f.getMacdSignal()) < 0;

        boolean histogramNegative = f.getMacdHistogram() != null
                && f.getMacdHistogram().compareTo(BigDecimal.ZERO) < 0;

        if (!plusDiBelowMinusDi) {
            log.info("Bearish momentum failed: plusDI >= minusDI | plusDI={} minusDI={}", f.getPlusDI(), f.getMinusDI());
        }
        if (!macdBelowSignal) {
            log.info("Bearish momentum failed: macd >= macdSignal | macd={} macdSignal={}", f.getMacd(), f.getMacdSignal());
        }
        if (!histogramNegative) {
            log.info("Bearish momentum failed: macdHistogram >= 0/null | macdHistogram={}", f.getMacdHistogram());
        }

        return plusDiBelowMinusDi && macdBelowSignal && histogramNegative;
    }

    private boolean hasAcceptableVolume(FeatureStore f) {
        boolean result = f.getRelativeVolume20() == null
                || f.getRelativeVolume20().compareTo(MIN_RELATIVE_VOLUME) >= 0;

        if (!result) {
            log.info("Volume failed: relativeVolume20 below minimum | relativeVolume20={} minimum={}",
                    f.getRelativeVolume20(), MIN_RELATIVE_VOLUME);
        }

        return result;
    }

    private boolean isValidLongTrigger(FeatureStore f) {
        boolean breakout = Boolean.TRUE.equals(f.getIsBullishBreakout());
        boolean pullback = Boolean.TRUE.equals(f.getIsBullishPullback());
        boolean entryBiasLong = "LONG".equalsIgnoreCase(f.getEntryBias());

        boolean result = breakout || pullback || entryBiasLong;


        if (!result) {
            log.info("Long trigger failed | breakout={} pullback={} entryBias={}", breakout, pullback, f.getEntryBias());
        }

        return result;
    }

    private boolean isValidShortTrigger(FeatureStore f) {
        boolean breakout = Boolean.TRUE.equals(f.getIsBearishBreakout());
        boolean pullback = Boolean.TRUE.equals(f.getIsBearishPullback());
        boolean entryBiasShort = SIDE_SHORT.equalsIgnoreCase(f.getEntryBias());

        boolean result =  breakout || pullback || entryBiasShort;
        if (!result) {
            log.info("Short trigger failed | breakout={} pullback={} entryBias={}", breakout, pullback, f.getEntryBias());
        }

        return result;
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

    private Optional<BigDecimal> getValidAtr(FeatureStore featureStore) {
        if (featureStore.getAtr() == null || featureStore.getAtr().compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        return Optional.of(featureStore.getAtr());
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