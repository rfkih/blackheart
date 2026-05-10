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
import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DefaultStrategyContextEnrichmentService implements StrategyContextEnrichmentService {

    /**
     * Map key used both as the contractual {@code executionMetadata} flag
     * (caller writes "backtest" / "live") and as a diagnostic provenance
     * label on enriched snapshots. The two usages are distinct in intent
     * but share the wire-format key, so a single constant prevents the
     * literal from drifting across call-sites.
     */
    private static final String SOURCE_KEY = "source";

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
        FeatureStore previousFeatureStore = null;

        boolean isBacktest = baseContext.getExecutionMetadata() != null
                && "backtest".equals(baseContext.getExecutionMetadata().get(SOURCE_KEY));

        if (!isBacktest
                && requirements.isRequireBiasTimeframe()
                && requirements.getBiasInterval() != null
                && !requirements.getBiasInterval().isBlank()) {
            // Bug fix: use last COMPLETED bias candle (end_time < now) not the current forming one
            LocalDateTime now = LocalDateTime.now();
            biasMarketData = marketDataRepository
                    .findLatestCompletedBySymbolAndInterval(baseContext.getAsset(), requirements.getBiasInterval(), now)
                    .orElse(null);
            if (biasMarketData != null) {
                biasFeatureStore = featureStoreRepository
                        .findLatestCompletedBySymbolAndInterval(baseContext.getAsset(), requirements.getBiasInterval(), now)
                        .orElse(null);
            }
        }

        // Bug fix: populate previousFeatureStore for live (backtest sets it manually after enrichment)
        if (!isBacktest
                && requirements.isRequirePreviousFeatureStore()
                && baseContext.getAsset() != null
                && baseContext.getInterval() != null
                && baseContext.getFeatureStore() != null
                && baseContext.getFeatureStore().getStartTime() != null) {
            previousFeatureStore = featureStoreRepository
                    .findPreviousBySymbolIntervalAndStartTime(
                            baseContext.getAsset(),
                            baseContext.getInterval(),
                            baseContext.getFeatureStore().getStartTime())
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
                .previousFeatureStore(previousFeatureStore)
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

    private static final String LABEL_BULL_TREND = "BULL_TREND";
    private static final String LABEL_BEAR_TREND = "BEAR_TREND";
    private static final String LABEL_RANGE = "RANGE";
    private static final BigDecimal TREND_BASE_SCORE = new BigDecimal("0.70");
    private static final BigDecimal TREND_BONUS = new BigDecimal("0.10");
    private static final BigDecimal ADX_TREND_THRESHOLD = new BigDecimal("18");
    private static final BigDecimal COMPRESSION_DEFAULT = new BigDecimal("0.20");

    private RegimeSnapshot buildRegimeSnapshot(
            BaseStrategyContext context,
            FeatureStore biasFeatureStore,
            MarketData biasMarketData
    ) {
        FeatureStore currentFeature = context.getFeatureStore();
        MarketData currentMarket = context.getMarketData();

        if (currentFeature == null || currentMarket == null || currentMarket.getClosePrice() == null) {
            return unknownRegimeSnapshot();
        }

        RegimeDetection detection = detectRegime(currentFeature, currentMarket.getClosePrice());
        BigDecimal trendScore = detection.trendScore();
        if (hasAdxTrend(currentFeature)) {
            trendScore = trendScore.add(TREND_BONUS);
        }
        trendScore = applyBiasConfirmation(detection.regimeLabel(), trendScore, biasFeatureStore, biasMarketData);
        if (trendScore.compareTo(BigDecimal.ONE) > 0) {
            trendScore = BigDecimal.ONE;
        }

        BigDecimal compressionScore = hasPositiveAtr(currentFeature) ? COMPRESSION_DEFAULT : BigDecimal.ZERO;

        return RegimeSnapshot.builder()
                .regimeLabel(detection.regimeLabel())
                .trendScore(trendScore)
                .compressionScore(compressionScore)
                .regimeConfidence(trendScore)
                .diagnostics(Map.of(
                        "biasFeaturePresent", biasFeatureStore != null,
                        "biasMarketPresent", biasMarketData != null
                ))
                .build();
    }

    private static RegimeSnapshot unknownRegimeSnapshot() {
        return RegimeSnapshot.builder()
                .regimeLabel("UNKNOWN")
                .trendScore(BigDecimal.ZERO)
                .compressionScore(BigDecimal.ZERO)
                .regimeConfidence(BigDecimal.ZERO)
                .diagnostics(Map.of("reason", "missing_current_market_or_feature"))
                .build();
    }

    /** Trend label + base trend score derived from EMA stack alignment. */
    private record RegimeDetection(String regimeLabel, BigDecimal trendScore) {}

    private static RegimeDetection detectRegime(FeatureStore feature, BigDecimal close) {
        if (feature.getEma50() == null || feature.getEma200() == null || feature.getEma50Slope() == null) {
            return new RegimeDetection(LABEL_RANGE, BigDecimal.ZERO);
        }
        boolean bullish = close.compareTo(feature.getEma50()) > 0
                && feature.getEma50().compareTo(feature.getEma200()) > 0
                && feature.getEma50Slope().compareTo(BigDecimal.ZERO) > 0;
        if (bullish) return new RegimeDetection(LABEL_BULL_TREND, TREND_BASE_SCORE);

        boolean bearish = close.compareTo(feature.getEma50()) < 0
                && feature.getEma50().compareTo(feature.getEma200()) < 0
                && feature.getEma50Slope().compareTo(BigDecimal.ZERO) < 0;
        if (bearish) return new RegimeDetection(LABEL_BEAR_TREND, TREND_BASE_SCORE);

        return new RegimeDetection(LABEL_RANGE, BigDecimal.ZERO);
    }

    private static boolean hasAdxTrend(FeatureStore feature) {
        return feature.getAdx() != null && feature.getAdx().compareTo(ADX_TREND_THRESHOLD) >= 0;
    }

    private static boolean hasPositiveAtr(FeatureStore feature) {
        return feature.getAtr() != null && feature.getAtr().compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Adds the bias-confirmation bonus when the higher-timeframe EMA stack
     * agrees with the current trend label. Returns trendScore unchanged when
     * any bias input is missing or the stacks disagree.
     */
    private static BigDecimal applyBiasConfirmation(
            String regimeLabel,
            BigDecimal trendScore,
            FeatureStore biasFeatureStore,
            MarketData biasMarketData) {
        if (biasFeatureStore == null
                || biasMarketData == null
                || biasMarketData.getClosePrice() == null
                || biasFeatureStore.getEma50() == null
                || biasFeatureStore.getEma200() == null) {
            return trendScore;
        }
        BigDecimal biasClose = biasMarketData.getClosePrice();
        boolean bullishBias = biasClose.compareTo(biasFeatureStore.getEma50()) > 0
                && biasFeatureStore.getEma50().compareTo(biasFeatureStore.getEma200()) > 0;
        boolean bearishBias = biasClose.compareTo(biasFeatureStore.getEma50()) < 0
                && biasFeatureStore.getEma50().compareTo(biasFeatureStore.getEma200()) < 0;
        boolean confirms = (LABEL_BULL_TREND.equals(regimeLabel) && bullishBias)
                || (LABEL_BEAR_TREND.equals(regimeLabel) && bearishBias);
        return confirms ? trendScore.add(TREND_BONUS) : trendScore;
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
                        SOURCE_KEY, "feature_store_atr"
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
                        SOURCE_KEY, "default_market_quality"
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