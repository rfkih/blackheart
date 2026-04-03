package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.BaseStrategyContext;
import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.MarketQualitySnapshot;
import id.co.blackheart.dto.strategy.RegimeSnapshot;
import id.co.blackheart.dto.strategy.RiskSnapshot;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.dto.strategy.StrategyRuntimeConfig;
import id.co.blackheart.dto.strategy.VolatilitySnapshot;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DefaultStrategyContextEnrichmentService implements StrategyContextEnrichmentService {

    private final FeatureStoreRepository featureStoreRepository;
    private final MarketDataRepository marketDataRepository;

    @Override
    public EnrichedStrategyContext enrich(BaseStrategyContext baseContext, StrategyRequirements requirements) {
        if (baseContext == null) {
            throw new IllegalArgumentException("BaseStrategyContext must not be null");
        }

        if (requirements == null) {
            requirements = StrategyRequirements.builder().build();
        }

        MarketData biasMarketData = null;
        FeatureStore biasFeatureStore = null;

        boolean isBacktest = baseContext.getExecutionMetadata() != null
                && "backtest".equals(baseContext.getExecutionMetadata().get("source"));

        if (!isBacktest
                && requirements.isRequireBiasTimeframe()
                && requirements.getBiasInterval() != null
                && !requirements.getBiasInterval().isBlank()) {
            biasFeatureStore = featureStoreRepository
                    .findLatestBySymbolAndInterval(baseContext.getAsset(), requirements.getBiasInterval())
                    .orElse(null);

            biasMarketData = marketDataRepository
                    .findLatestBySymbolAndInterval(baseContext.getAsset(), requirements.getBiasInterval())
                    .orElse(null);
        }

        RegimeSnapshot regimeSnapshot = requirements.isRequireRegimeSnapshot()
                ? buildRegimeSnapshot(baseContext, biasFeatureStore, biasMarketData)
                : null;

        VolatilitySnapshot volatilitySnapshot = requirements.isRequireVolatilitySnapshot()
                ? buildVolatilitySnapshot(baseContext)
                : null;

        RiskSnapshot riskSnapshot = requirements.isRequireRiskSnapshot()
                ? buildRiskSnapshot(baseContext, volatilitySnapshot)
                : null;

        MarketQualitySnapshot marketQualitySnapshot =requirements.isRequireMarketQualitySnapshot()
                ? buildMarketQualitySnapshot(baseContext)
                : null;

        StrategyRuntimeConfig runtimeConfig = buildRuntimeConfig(baseContext);

        return EnrichedStrategyContext.builder()
                .account(baseContext.getAccount())
                .accountStrategy(baseContext.getAccountStrategy())
                .asset(baseContext.getAsset())
                .interval(baseContext.getInterval())
                .marketData(baseContext.getMarketData())
                .featureStore(baseContext.getFeatureStore())
                .biasMarketData(biasMarketData)
                .biasFeatureStore(biasFeatureStore)
                .regimeSnapshot(regimeSnapshot)
                .volatilitySnapshot(volatilitySnapshot)
                .riskSnapshot(riskSnapshot)
                .marketQualitySnapshot(marketQualitySnapshot)
                .positionSnapshot(baseContext.getPositionSnapshot())
                .hasOpenPosition(baseContext.getHasOpenPosition())
                .openPositionCount(baseContext.getOpenPositionCount())
                .executionMetadata(baseContext.getExecutionMetadata())
                .cashBalance(baseContext.getCashBalance())
                .assetBalance(baseContext.getAssetBalance())
                .allowLong(baseContext.getAllowLong())
                .allowShort(baseContext.getAllowShort())
                .maxOpenPositions(baseContext.getMaxOpenPositions())
                .currentOpenTradeCount(baseContext.getCurrentOpenTradeCount())
                .runtimeConfig(runtimeConfig)
                .diagnostics(baseContext.getDiagnostics())
                .build();
    }

    private RegimeSnapshot buildRegimeSnapshot(
            BaseStrategyContext context,
            FeatureStore biasFeatureStore,
            MarketData biasMarketData
    ) {
        FeatureStore currentFeature = context.getFeatureStore();
        MarketData currentMarket = context.getMarketData();

        if (currentFeature == null || currentMarket == null || currentMarket.getClosePrice() == null) {
            return RegimeSnapshot.builder()
                    .regimeLabel("UNKNOWN")
                    .trendScore(BigDecimal.ZERO)
                    .compressionScore(BigDecimal.ZERO)
                    .regimeConfidence(BigDecimal.ZERO)
                    .diagnostics(Map.of(
                            "reason", "missing_current_market_or_feature"
                    ))
                    .build();
        }

        BigDecimal trendScore = BigDecimal.ZERO;
        String regimeLabel = "RANGE";

        if (currentFeature.getEma50() != null
                && currentFeature.getEma200() != null
                && currentFeature.getEma50Slope() != null) {
            BigDecimal close = currentMarket.getClosePrice();

            boolean bullish = close.compareTo(currentFeature.getEma50()) > 0
                    && currentFeature.getEma50().compareTo(currentFeature.getEma200()) > 0
                    && currentFeature.getEma50Slope().compareTo(BigDecimal.ZERO) > 0;

            boolean bearish = close.compareTo(currentFeature.getEma50()) < 0
                    && currentFeature.getEma50().compareTo(currentFeature.getEma200()) < 0
                    && currentFeature.getEma50Slope().compareTo(BigDecimal.ZERO) < 0;

            if (bullish) {
                regimeLabel = "BULL_TREND";
                trendScore = new BigDecimal("0.70");
            } else if (bearish) {
                regimeLabel = "BEAR_TREND";
                trendScore = new BigDecimal("0.70");
            }
        }

        if (currentFeature.getAdx() != null && currentFeature.getAdx().compareTo(new BigDecimal("18")) >= 0) {
            trendScore = trendScore.add(new BigDecimal("0.10"));
        }

        if (biasFeatureStore != null
                && biasMarketData != null
                && biasMarketData.getClosePrice() != null
                && biasFeatureStore.getEma50() != null
                && biasFeatureStore.getEma200() != null) {
            boolean bullishBias = biasMarketData.getClosePrice().compareTo(biasFeatureStore.getEma50()) > 0
                    && biasFeatureStore.getEma50().compareTo(biasFeatureStore.getEma200()) > 0;

            boolean bearishBias = biasMarketData.getClosePrice().compareTo(biasFeatureStore.getEma50()) < 0
                    && biasFeatureStore.getEma50().compareTo(biasFeatureStore.getEma200()) < 0;

            if (("BULL_TREND".equals(regimeLabel) && bullishBias)
                    || ("BEAR_TREND".equals(regimeLabel) && bearishBias)) {
                trendScore = trendScore.add(new BigDecimal("0.10"));
            }
        }

        if (trendScore.compareTo(BigDecimal.ONE) > 0) {
            trendScore = BigDecimal.ONE;
        }

        BigDecimal compressionScore = BigDecimal.ZERO;
        if (currentFeature.getAtr() != null && currentFeature.getAtr().compareTo(BigDecimal.ZERO) > 0) {
            compressionScore = new BigDecimal("0.20");
        }

        return RegimeSnapshot.builder()
                .regimeLabel(regimeLabel)
                .trendScore(trendScore)
                .compressionScore(compressionScore)
                .regimeConfidence(trendScore)
                .diagnostics(Map.of(
                        "biasFeaturePresent", biasFeatureStore != null,
                        "biasMarketPresent", biasMarketData != null
                ))
                .build();
    }

    private VolatilitySnapshot buildVolatilitySnapshot(BaseStrategyContext context) {
        FeatureStore feature = context.getFeatureStore();

        BigDecimal atr = feature != null && feature.getAtr() != null
                ? feature.getAtr()
                : BigDecimal.ZERO;

        BigDecimal atrPercentile = BigDecimal.ZERO;
        BigDecimal realizedVol = BigDecimal.ZERO;
        BigDecimal forecastVol = BigDecimal.ZERO;
        BigDecimal jumpRiskScore = BigDecimal.ZERO;

        if (atr.compareTo(BigDecimal.ZERO) > 0) {
            atrPercentile = new BigDecimal("0.50");
            realizedVol = atr;
            forecastVol = atr;
        }

        return VolatilitySnapshot.builder()
                .atr(atr)
                .atrPercentile(atrPercentile)
                .realizedVol(realizedVol)
                .forecastVol(forecastVol)
                .jumpRiskScore(jumpRiskScore)
                .diagnostics(Map.of(
                        "source", "feature_store_atr"
                ))
                .build();
    }

    private RiskSnapshot buildRiskSnapshot(BaseStrategyContext context, VolatilitySnapshot volatilitySnapshot) {
        BigDecimal baseRiskPct = context.getRiskPerTradePct() == null
                ? BigDecimal.ZERO
                : context.getRiskPerTradePct();

        BigDecimal riskMultiplier = BigDecimal.ONE;

        if (volatilitySnapshot != null
                && volatilitySnapshot.getJumpRiskScore() != null
                && volatilitySnapshot.getJumpRiskScore().compareTo(new BigDecimal("0.70")) > 0) {
            riskMultiplier = new BigDecimal("0.50");
        }

        BigDecimal finalRiskPct = baseRiskPct.multiply(riskMultiplier);

        return RiskSnapshot.builder()
                .baseRiskPct(baseRiskPct)
                .finalRiskPct(finalRiskPct)
                .riskMultiplier(riskMultiplier)
                .maxAllowedPositionSize(BigDecimal.ZERO)
                .diagnostics(Map.of(
                        "volatilitySnapshotPresent", volatilitySnapshot != null
                ))
                .build();
    }

    private MarketQualitySnapshot buildMarketQualitySnapshot(BaseStrategyContext context) {
        FeatureStore feature = context.getFeatureStore();

        BigDecimal volumeScore = BigDecimal.ONE;
        if (feature != null && feature.getRelativeVolume20() != null) {
            volumeScore = feature.getRelativeVolume20();
        }

        BigDecimal liquidityScore = BigDecimal.ONE;
        BigDecimal executionQualityScore = BigDecimal.ONE;
        Boolean tradable = Boolean.TRUE;

        return MarketQualitySnapshot.builder()
                .liquidityScore(liquidityScore)
                .volumeScore(volumeScore)
                .executionQualityScore(executionQualityScore)
                .tradable(tradable)
                .diagnostics(Map.of(
                        "source", "default_market_quality"
                ))
                .build();
    }

    private StrategyRuntimeConfig buildRuntimeConfig(BaseStrategyContext context) {
        String strategyCode = context.getAccountStrategy() != null
                ? context.getAccountStrategy().getStrategyCode()
                : null;

        return StrategyRuntimeConfig.builder()
                .strategyCode(strategyCode)
                .strategyVersion("v1")
                .rawConfig(Map.of())
                .minSignalScore(new BigDecimal("0.55"))
                .minRegimeScore(new BigDecimal("0.50"))
                .maxJumpRiskScore(BigDecimal.ONE)
                .defaultRiskMultiplier(BigDecimal.ONE)
                .maxRiskMultiplier(BigDecimal.ONE)
                .minRiskMultiplier(BigDecimal.ZERO)
                .allowLong(context.getAllowLong())
                .allowShort(context.getAllowShort())
                .maxOpenPositions(context.getMaxOpenPositions())
                .riskPerTradePct(context.getRiskPerTradePct())
                .build();
    }
}