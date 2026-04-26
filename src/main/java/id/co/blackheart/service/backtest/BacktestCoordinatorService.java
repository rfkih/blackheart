package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestExecutionSummary;
import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.dto.strategy.BaseStrategyContext;
import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.dto.tradelistener.ListenerContext;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.BacktestTradePosition;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.strategy.StrategyContextEnrichmentService;
import id.co.blackheart.service.strategy.StrategyExecutor;
import id.co.blackheart.service.strategy.StrategyExecutorFactory;
import id.co.blackheart.service.tradelistener.TradeListenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestCoordinatorService {

    private static final String MONITOR_INTERVAL = "5m";
    private static final String EXECUTION_SOURCE = "backtest";

    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;
    private final StrategyExecutorFactory strategyExecutorFactory;
    private final StrategyContextEnrichmentService strategyContextEnrichmentService;
    private final TradeListenerService tradeListenerService;
    private final BacktestTradeExecutorService backtestTradeExecutorService;
    private final BacktestMetricsService backtestMetricsService;
    private final BacktestPositionSnapshotMapper backtestPositionSnapshotMapper;
    private final BacktestStateService backtestStateService;
    private final BacktestPersistenceService backtestPersistenceService;
    private final BacktestEquityPointRecorder backtestEquityPointRecorder;
    private final BacktestProgressTracker progressTracker;
    private final id.co.blackheart.repository.AccountStrategyRepository accountStrategyRepository;

    /** Report progress at most every N candles. With MIN_INTERVAL_MS also
     *  throttling inside the tracker, this just cheaply avoids calling into
     *  it on every single tick. */
    private static final int PROGRESS_TICK_EVERY = 100;

    public BacktestExecutionSummary execute(BacktestRun backtestRun) {
        validateBacktestRun(backtestRun);

        String strategyInterval = backtestRun.getInterval();

        List<MarketData> monitorCandles = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(),
                MONITOR_INTERVAL,
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        List<MarketData> strategyCandles = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(),
                strategyInterval,
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        List<FeatureStore> strategyFeatures = featureStoreRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(),
                strategyInterval,
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        if (monitorCandles == null || monitorCandles.isEmpty()) {
            throw new IllegalArgumentException("No monitor market data found for interval: " + MONITOR_INTERVAL);
        }

        if (strategyCandles == null || strategyCandles.isEmpty()) {
            throw new IllegalArgumentException("No strategy market data found for interval: " + strategyInterval);
        }

        if (strategyFeatures == null || strategyFeatures.isEmpty()) {
            throw new IllegalArgumentException("No feature store found for interval: " + strategyInterval);
        }

        Map<LocalDateTime, MarketData> strategyCandleByEndTime = strategyCandles.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        MarketData::getEndTime,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));

        Map<LocalDateTime, FeatureStore> strategyFeatureByStartTime = strategyFeatures.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        FeatureStore::getStartTime,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));

        List<FeatureStore> sortedStrategyFeatures = strategyFeatures.stream()
                .filter(f -> f != null && f.getStartTime() != null)
                .sorted(Comparator.comparing(FeatureStore::getStartTime))
                .toList();

        BacktestState state = BacktestState.initial(backtestRun);

        // Prime the progress bar early — users should see the run leave 0% as
        // soon as it enters the tight candle loop, not after the first tick fires.
        progressTracker.report(backtestRun.getBacktestRunId(), 0.01);

        List<StrategyExecutorEntry> executors = resolveStrategyExecutors(backtestRun);
        StrategyRequirements requirements = executors.size() == 1
                ? executors.getFirst().executor().getRequirements()
                : mergeRequirements(executors.stream()
                        .map(e -> e.executor().getRequirements())
                        .toList());

        BiasData biasData = preloadBiasData(backtestRun, requirements);

        // Phase B2 — per-strategy interval contexts. When the run was
        // submitted with a strategyIntervals map, each strategy fires only
        // on its own timeframe's bar closes. Otherwise every strategy
        // shares the primary interval context (legacy behaviour).
        Map<String, IntervalContext> perStrategyContext = buildPerStrategyContexts(
                backtestRun, executors, strategyInterval,
                strategyCandleByEndTime, strategyFeatureByStartTime, sortedStrategyFeatures);

        // Resolve per-strategy capital allocation ONCE at run start. Without
        // this, every monitor tick called accountStrategyRepository.findById
        // for any strategy lacking a wizard override — pathological N+1 on
        // long backtests (260k+ ticks × N strategies).
        Map<String, BigDecimal> allocationByStrategy = resolveAllocationsForRun(backtestRun, executors);

        final int totalCandles = monitorCandles.size();
        int processed = 0;
        for (MarketData monitorCandle : monitorCandles) {
            backtestTradeExecutorService.fillPendingEntry(
                    backtestRun, state, monitorCandle.getOpenPrice(), monitorCandle.getStartTime()
            );

            boolean anyPositionClosed = handleListenerStep(backtestRun, state, monitorCandle);

            // Run strategy even when a position was closed if there are still open positions
            // (e.g. TP1 fired on TP1_RUNNER — runner needs UPDATE_POSITION_MANAGEMENT on same bar).
            // When all positions closed via listener, skip strategy to avoid same-bar re-entry.
            if (!anyPositionClosed || hasAnyOpenTrade(state)) {
                handleStrategyStep(
                        backtestRun,
                        state,
                        monitorCandle,
                        executors,
                        requirements,
                        biasData,
                        perStrategyContext,
                        allocationByStrategy
                );
            }

            backtestStateService.checkIntraBarDrawdown(state, monitorCandle.getLowPrice());
            backtestStateService.checkIntraBarDrawdown(state, monitorCandle.getHighPrice());
            backtestStateService.updateEquityAndDrawdown(state, monitorCandle.getClosePrice());
            backtestEquityPointRecorder.record(state, backtestRun, monitorCandle);

            processed++;
            if (processed % PROGRESS_TICK_EVERY == 0) {
                progressTracker.reportStep(
                        backtestRun.getBacktestRunId(),
                        processed,
                        totalCandles
                );
            }
        }

        MarketData finalCandle = monitorCandles.getLast();

        forceCloseRemainingOpenTrade(backtestRun, state, finalCandle);
        // Discard any pending entries that never filled — they don't represent
        // real exposure (no positions were ever opened) but leaving them in
        // state would surface as ghost entries in any post-run inspection.
        if (state.getPendingEntriesByStrategy() != null) {
            int leftover = state.getPendingEntriesByStrategy().size();
            if (leftover > 0) {
                log.debug("Discarding {} unfilled pending entries at end-of-run", leftover);
                state.getPendingEntriesByStrategy().clear();
            }
        }
        state.setPendingEntry(null);
        backtestStateService.updateEquityAndDrawdown(state, finalCandle.getClosePrice());

        state.setEquityPoints(new ArrayList<>(state.getEquityPointIndex().values()));

        BacktestExecutionSummary summary = backtestMetricsService.buildSummary(backtestRun, state);

        backtestPersistenceService.persist(backtestRun, state, summary, true);

        return summary;
    }

    private boolean handleListenerStep(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData monitorCandle
    ) {
        // Phase B2 — flatten positions across ALL active trades so every
        // open leg gets stop-loss / TP checks regardless of which strategy
        // owns it. Falls back to the legacy single-slot iteration when no
        // multi-trade state has been registered.
        java.util.List<BacktestTradePosition> allActivePositions = new java.util.ArrayList<>();
        if (state.getActiveTradePositionsByStrategy() != null
                && !state.getActiveTradePositionsByStrategy().isEmpty()) {
            for (java.util.List<BacktestTradePosition> perStrategy
                    : state.getActiveTradePositionsByStrategy().values()) {
                if (perStrategy != null) allActivePositions.addAll(perStrategy);
            }
        } else if (state.getActiveTradePositions() != null) {
            allActivePositions.addAll(state.getActiveTradePositions());
        }
        if (allActivePositions.isEmpty()) {
            return false;
        }

        boolean anyClosed = false;

        for (BacktestTradePosition position : allActivePositions) {
            if (!"OPEN".equalsIgnoreCase(position.getStatus())) {
                continue;
            }

            updatePriceExtremes(position, monitorCandle);
            updateTrailingStop(position);

            PositionSnapshot snapshot = backtestPositionSnapshotMapper.toSnapshot(position);

            ListenerContext listenerContext = ListenerContext.builder()
                    .asset(backtestRun.getAsset())
                    .interval(MONITOR_INTERVAL)
                    .positionSnapshot(snapshot)
                    .latestPrice(monitorCandle.getClosePrice())
                    .candleOpen(monitorCandle.getOpenPrice())
                    .candleHigh(monitorCandle.getHighPrice())
                    .candleLow(monitorCandle.getLowPrice())
                    .build();

            ListenerDecision listenerDecision = tradeListenerService.evaluate(listenerContext);

            if (!listenerDecision.isTriggered()) {
                continue;
            }

            backtestTradeExecutorService.closeSinglePositionFromListener(
                    backtestRun,
                    state,
                    position,
                    listenerDecision.getExitPrice(),
                    listenerDecision.getExitReason(),
                    monitorCandle.getEndTime()
            );

            anyClosed = true;
        }

        return anyClosed;
    }

    /**
     * Phase B2 — multi-interval routing. Each strategy is dispatched only
     * when its own timeframe's bar closes at the current monitor tick.
     * Single-interval runs degenerate to the legacy "all strategies share
     * one bar" behaviour because every strategy's IntervalContext points
     * at the same shared maps.
     */
    private void handleStrategyStep(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData monitorCandle,
            List<StrategyExecutorEntry> executors,
            StrategyRequirements requirements,
            BiasData biasData,
            Map<String, IntervalContext> perStrategyContext,
            Map<String, BigDecimal> allocationByStrategy
    ) {
        // Phase B1/B2 — snapshot/count/hasOpenTrade are PER-STRATEGY now
        // (each strategy sees only its own positions). Each loop iteration
        // builds a FRESH executionMetadata map (Fix H) — sharing one across
        // strategies leaks the previous strategy's hasOpenTrade flag if any
        // downstream code stashes the map for later use.

        if (executors.size() == 1) {
            // ── Single-strategy path (unchanged behaviour) ────────────────────
            StrategyExecutorEntry only = executors.getFirst();
            IntervalContext ic = perStrategyContext.get(only.code());
            if (ic == null) return;
            MarketData strategyCandle = ic.candleByEndTime().get(monitorCandle.getEndTime());
            if (strategyCandle == null) return;
            FeatureStore strategyFeature = ic.featureByStartTime().get(strategyCandle.getStartTime());
            if (strategyFeature == null) {
                log.warn("FeatureStore missing for symbol={} interval={} startTime={}",
                        backtestRun.getAsset(), ic.interval(), strategyCandle.getStartTime());
                return;
            }

            PositionSnapshot positionSnapshot = buildPositionSnapshotFor(state, only.code());
            int openPositionCount = countOpenPositionsFor(state, only.code());
            boolean strategyHasTrade = hasOpenTradeFor(state, only.code());

            AccountStrategy syntheticAs = buildSyntheticAccountStrategy(backtestRun, only.code(), ic.interval(), allocationByStrategy);
            EnrichedStrategyContext enrichedContext = buildAndEnrichContext(
                    backtestRun, state, ic.interval(), strategyCandle, strategyFeature,
                    positionSnapshot, openPositionCount, strategyHasTrade,
                    buildExecutionMetadata(backtestRun, strategyHasTrade),
                    syntheticAs, requirements, biasData, ic.sortedFeatures(), monitorCandle);

            StrategyDecision decision = only.executor().execute(enrichedContext);
            log.debug("Strategy decision reason={}", decision.getReason());
            backtestTradeExecutorService.execute(backtestRun, state, enrichedContext, decision);
            return;
        }

        // ── Multi-strategy orchestrator (Phase B1 cap + B2 multi-interval) ──
        int cap = backtestRun.getMaxConcurrentStrategies() != null
                ? backtestRun.getMaxConcurrentStrategies()
                : 1;

        // (1) Existing owners manage their trades — but ONLY when this
        // monitor tick aligns with the owner's strategy interval.
        java.util.Set<String> ownersAtTickStart = state.getActiveTradesByStrategy() == null
                ? java.util.Collections.emptySet()
                : new java.util.HashSet<>(state.getActiveTradesByStrategy().keySet());
        for (String ownerCode : ownersAtTickStart) {
            StrategyExecutorEntry ownerEntry = findExecutorByCode(executors, ownerCode);
            if (ownerEntry == null) continue;
            IntervalContext ic = perStrategyContext.get(ownerCode);
            if (ic == null) continue;
            MarketData strategyCandle = ic.candleByEndTime().get(monitorCandle.getEndTime());
            if (strategyCandle == null) continue;  // owner's bar isn't closing yet
            FeatureStore strategyFeature = ic.featureByStartTime().get(strategyCandle.getStartTime());
            if (strategyFeature == null) continue;

            PositionSnapshot ownerSnapshot = buildPositionSnapshotFor(state, ownerEntry.code());
            int ownerOpenCount = countOpenPositionsFor(state, ownerEntry.code());

            AccountStrategy syntheticAs = buildSyntheticAccountStrategy(backtestRun, ownerEntry.code(), ic.interval(), allocationByStrategy);
            StrategyRequirements ownerRequirements = ownerEntry.executor().getRequirements();
            EnrichedStrategyContext enrichedContext = buildAndEnrichContext(
                    backtestRun, state, ic.interval(), strategyCandle, strategyFeature,
                    ownerSnapshot, ownerOpenCount, true,
                    buildExecutionMetadata(backtestRun, true),
                    syntheticAs, ownerRequirements, biasData, ic.sortedFeatures(), monitorCandle);

            StrategyDecision decision = ownerEntry.executor().execute(enrichedContext);
            log.debug("Orchestrator active-trade | owner={} interval={} reason={}",
                    ownerEntry.code(), ic.interval(), decision.getReason());
            backtestTradeExecutorService.execute(backtestRun, state, enrichedContext, decision);
        }

        // (2) Strategies without a trade can fire entries — gated by cap
        // AND by whether their own timeframe has a bar closing at this tick.
        for (StrategyExecutorEntry entry : executors) {
            if (state.hasActiveTradeFor(entry.code())) continue;
            // Skip strategies that already have a pending entry queued —
            // storePendingEntry would silently reject anyway, but filtering
            // here avoids running the strategy executor for nothing.
            if (state.hasPendingEntryFor(entry.code())) continue;
            // Cap check counts BOTH active trades and pending entries:
            // pending entries fill on the next bar and become active, so
            // they're part of the future concurrent-strategy count. Without
            // this, multiple strategies signaling entries on the same monitor
            // candle could each queue a pending and all fill next bar,
            // bypassing maxConcurrentStrategies.
            int futureActive = state.countActiveTrades() + state.countPendingEntries();
            if (futureActive >= cap) {
                log.debug("Orchestrator entry skipped | strategy={} reason=cap (active={} pending={} cap={})",
                        entry.code(), state.countActiveTrades(), state.countPendingEntries(), cap);
                break;
            }
            IntervalContext ic = perStrategyContext.get(entry.code());
            if (ic == null) continue;
            MarketData strategyCandle = ic.candleByEndTime().get(monitorCandle.getEndTime());
            if (strategyCandle == null) continue;
            FeatureStore strategyFeature = ic.featureByStartTime().get(strategyCandle.getStartTime());
            if (strategyFeature == null) continue;

            // Entry path: this strategy has no trade by construction (filtered
            // above). Snapshot/count/hasOpenTrade reflect THIS strategy's
            // empty state, not "any strategy in the cap".
            PositionSnapshot entrySnapshot = PositionSnapshot.builder().hasOpenPosition(false).build();

            AccountStrategy syntheticAs = buildSyntheticAccountStrategy(backtestRun, entry.code(), ic.interval(), allocationByStrategy);
            StrategyRequirements entryRequirements = entry.executor().getRequirements();
            EnrichedStrategyContext enrichedContext = buildAndEnrichContext(
                    backtestRun, state, ic.interval(), strategyCandle, strategyFeature,
                    entrySnapshot, 0, false,
                    buildExecutionMetadata(backtestRun, false),
                    syntheticAs, entryRequirements, biasData,
                    ic.sortedFeatures(), monitorCandle);

            StrategyDecision decision = entry.executor().execute(enrichedContext);
            if (isEntryDecision(decision)) {
                if (decision.getStrategyCode() == null || decision.getStrategyCode().isBlank()) {
                    decision.setStrategyCode(entry.code());
                }
                log.debug("Orchestrator entry | strategy={} interval={} side={}",
                        entry.code(), ic.interval(), decision.getSide());
                backtestTradeExecutorService.execute(backtestRun, state, enrichedContext, decision);
            }
        }
    }

    /**
     * Per-strategy market data context. When the run has a single primary
     * interval, every strategy's IntervalContext points at the same maps
     * (no extra DB load). When {@code strategyIntervals} is set, each
     * unique interval gets its own load + maps.
     */
    private record IntervalContext(
            String interval,
            Map<LocalDateTime, MarketData> candleByEndTime,
            Map<LocalDateTime, FeatureStore> featureByStartTime,
            List<FeatureStore> sortedFeatures
    ) {}

    /**
     * Build the per-strategy interval lookup used by
     * {@link #handleStrategyStep}. Loads at most one extra candle/feature
     * stream per unique interval declared in
     * {@code BacktestRun.strategyIntervals}; all strategies on the
     * primary interval share the already-loaded primary maps.
     */
    private Map<String, IntervalContext> buildPerStrategyContexts(
            BacktestRun backtestRun,
            List<StrategyExecutorEntry> executors,
            String primaryInterval,
            Map<LocalDateTime, MarketData> primaryCandleByEndTime,
            Map<LocalDateTime, FeatureStore> primaryFeatureByStartTime,
            List<FeatureStore> primarySortedFeatures
    ) {
        Map<String, String> intervalsByCode = backtestRun.getStrategyIntervals();
        IntervalContext primaryContext = new IntervalContext(
                primaryInterval, primaryCandleByEndTime,
                primaryFeatureByStartTime, primarySortedFeatures);

        Map<String, IntervalContext> out = new java.util.LinkedHashMap<>();
        // Cache loaded streams by interval so two strategies on the same
        // non-primary timeframe share the load. The cache key is the raw
        // interval string (CASE-PRESERVING): the @Pattern validator already
        // canonicalises case, and "1M" (month) vs "1m" (minute) are
        // genuinely different timeframes — toLowerCase'ing them would silently
        // serve month data to a minute-resolution strategy.
        Map<String, IntervalContext> byInterval = new java.util.HashMap<>();
        byInterval.put(primaryInterval, primaryContext);

        for (StrategyExecutorEntry entry : executors) {
            String code = entry.code();
            String resolved = primaryInterval;
            if (intervalsByCode != null) {
                String override = intervalsByCode.get(code);
                if (override == null && code != null) {
                    override = intervalsByCode.get(code.toUpperCase());
                }
                if (override != null && !override.isBlank()) resolved = override;
            }
            // computeIfAbsent's lambda needs an effectively-final reference
            // — capture the resolved interval into a final local first.
            final String resolvedFinal = resolved;
            IntervalContext ctx = byInterval.computeIfAbsent(resolved,
                    k -> loadIntervalContext(backtestRun, resolvedFinal));
            out.put(code, ctx);
        }
        return out;
    }

    private IntervalContext loadIntervalContext(BacktestRun backtestRun, String interval) {
        List<MarketData> candles = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(), interval,
                backtestRun.getStartTime(), backtestRun.getEndTime());
        List<FeatureStore> features = featureStoreRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(), interval,
                backtestRun.getStartTime(), backtestRun.getEndTime());

        // Phase B2 — fail loud, not silent. The single-strategy path throws
        // when its primary interval has no data; the multi-interval path
        // must do the same so a strategy configured on (e.g.) 30m with no
        // 30m candles in the window doesn't silently produce zero trades.
        // Also catches the "interval coarser than the run window" case
        // (1d strategy on a 6h backtest) — same symptom, same diagnosis.
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException(
                    "No strategy market data found for symbol=" + backtestRun.getAsset()
                            + " interval=" + interval
                            + " between " + backtestRun.getStartTime()
                            + " and " + backtestRun.getEndTime()
                            + " — pick a different interval or extend the date range.");
        }
        if (features == null || features.isEmpty()) {
            throw new IllegalArgumentException(
                    "No feature store data found for symbol=" + backtestRun.getAsset()
                            + " interval=" + interval
                            + " between " + backtestRun.getStartTime()
                            + " and " + backtestRun.getEndTime()
                            + " — strategies on this interval cannot evaluate without features.");
        }
        Map<LocalDateTime, MarketData> candleByEndTime = candles.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(MarketData::getEndTime, Function.identity(),
                        (existing, replacement) -> existing));
        Map<LocalDateTime, FeatureStore> featureByStartTime = features.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(FeatureStore::getStartTime, Function.identity(),
                        (existing, replacement) -> existing));
        List<FeatureStore> sortedFeatures = features.stream()
                .filter(f -> f != null && f.getStartTime() != null)
                .sorted(Comparator.comparing(FeatureStore::getStartTime))
                .toList();
        log.info("Loaded multi-interval stream | symbol={} interval={} candles={} features={}",
                backtestRun.getAsset(), interval, candles.size(), features.size());
        return new IntervalContext(interval, candleByEndTime, featureByStartTime, sortedFeatures);
    }

    /**
     * Builds a BaseStrategyContext, enriches it, and applies bias + previousFeatureStore.
     * Extracted to avoid duplicating this block across single/multi-strategy paths.
     */
    private EnrichedStrategyContext buildAndEnrichContext(
            BacktestRun backtestRun,
            BacktestState state,
            String strategyInterval,
            MarketData strategyCandle,
            FeatureStore strategyFeature,
            PositionSnapshot positionSnapshot,
            int openPositionCount,
            boolean hasOpenTrade,
            Map<String, Object> executionMetadata,
            AccountStrategy syntheticAs,
            StrategyRequirements requirements,
            BiasData biasData,
            List<FeatureStore> sortedStrategyFeatures,
            MarketData monitorCandle
    ) {
        BaseStrategyContext baseContext = BaseStrategyContext.builder()
                .account(null)
                .accountStrategy(syntheticAs)
                .asset(backtestRun.getAsset())
                .interval(strategyInterval)
                .marketData(strategyCandle)
                .featureStore(strategyFeature)
                .positionSnapshot(positionSnapshot)
                .hasOpenPosition(positionSnapshot != null && positionSnapshot.isHasOpenPosition())
                .openPositionCount(openPositionCount)
                .executionMetadata(executionMetadata)
                .cashBalance(state.getCashBalance())
                .assetBalance(resolveSyntheticAssetBalanceForBacktest(state, strategyCandle))
                .allowLong(resolveAllowLong(backtestRun))
                .allowShort(resolveAllowShort(backtestRun))
                .maxOpenPositions(resolveMaxOpenPositions(backtestRun))
                .currentOpenTradeCount(hasOpenTrade ? 1 : 0)
                .riskPerTradePct(backtestRun.getRiskPerTradePct())
                .build();

        EnrichedStrategyContext enrichedContext =
                strategyContextEnrichmentService.enrich(baseContext, requirements);

        if (requirements != null && requirements.isRequireBiasTimeframe()) {
            MarketData resolvedBiasMarket = resolveLatestCompletedBiasCandle(
                    biasData.biasCandles(), monitorCandle.getEndTime());
            enrichedContext.setBiasMarketData(resolvedBiasMarket);
            if (resolvedBiasMarket != null) {
                enrichedContext.setBiasFeatureStore(
                        biasData.biasFeatureByStartTime().get(resolvedBiasMarket.getStartTime()));
            }
        }

        if (requirements != null && requirements.isRequirePreviousFeatureStore()) {
            enrichedContext.setPreviousFeatureStore(
                    resolvePreviousFeatureStore(sortedStrategyFeatures, strategyCandle.getStartTime()));
        }

        return enrichedContext;
    }

    private boolean isEntryDecision(StrategyDecision decision) {
        if (decision == null || decision.getDecisionType() == null) return false;
        return decision.getDecisionType() == id.co.blackheart.util.TradeConstant.DecisionType.OPEN_LONG
                || decision.getDecisionType() == id.co.blackheart.util.TradeConstant.DecisionType.OPEN_SHORT;
    }

    private BiasData preloadBiasData(BacktestRun backtestRun, StrategyRequirements requirements) {
        if (requirements == null
                || !requirements.isRequireBiasTimeframe()
                || requirements.getBiasInterval() == null
                || requirements.getBiasInterval().isBlank()) {
            return new BiasData(List.of(), Map.of());
        }

        List<MarketData> biasCandles = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(),
                requirements.getBiasInterval(),
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        List<FeatureStore> biasFeatures = featureStoreRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(),
                requirements.getBiasInterval(),
                backtestRun.getStartTime(),
                backtestRun.getEndTime()
        );

        Map<LocalDateTime, FeatureStore> biasFeatureByStartTime = biasFeatures == null
                ? Map.of()
                : biasFeatures.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        FeatureStore::getStartTime,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));

        List<MarketData> sortedBiasCandles = biasCandles == null
                ? List.of()
                : biasCandles.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(MarketData::getEndTime))
                .toList();

        return new BiasData(sortedBiasCandles, biasFeatureByStartTime);
    }

    private FeatureStore resolvePreviousFeatureStore(
            List<FeatureStore> sortedFeatures,
            LocalDateTime currentStartTime
    ) {
        if (sortedFeatures == null || sortedFeatures.isEmpty() || currentStartTime == null) {
            return null;
        }
        FeatureStore prev = null;
        for (FeatureStore f : sortedFeatures) {
            if (f.getStartTime().isBefore(currentStartTime)) {
                prev = f;
            } else {
                break;
            }
        }
        return prev;
    }

    private MarketData resolveLatestCompletedBiasCandle(
            List<MarketData> biasCandles,
            LocalDateTime currentTime
    ) {
        if (biasCandles == null || biasCandles.isEmpty() || currentTime == null) {
            return null;
        }

        return biasCandles.stream()
                .filter(candle -> candle.getEndTime() != null && !candle.getEndTime().isAfter(currentTime))
                .max(Comparator.comparing(MarketData::getEndTime))
                .orElse(null);
    }

    /**
     * Per-iteration execution-metadata map. Each strategy call gets its own
     * instance so a downstream consumer that holds onto the reference can't
     * see another strategy's hasOpenTrade flag bleed through (Fix H).
     */
    private Map<String, Object> buildExecutionMetadata(BacktestRun backtestRun, boolean hasOpenTrade) {
        Map<String, Object> meta = new HashMap<>(3);
        meta.put("source", EXECUTION_SOURCE);
        meta.put("backtestRunId", backtestRun.getBacktestRunId());
        meta.put("hasOpenTrade", hasOpenTrade);
        return meta;
    }

    /**
     * Phase B1/B2 — multi-trade-aware snapshot. Each strategy sees ONLY its
     * own positions, so {@code context.hasOpenPosition} reflects the calling
     * strategy's state, not whichever trade was most recently opened across
     * the cap. Falls back to the legacy mirror when the per-strategy map is
     * empty (backwards compatibility for single-strategy runs).
     */
    private PositionSnapshot buildPositionSnapshotFor(BacktestState state, String strategyCode) {
        BacktestTrade trade = strategyTrade(state, strategyCode);
        List<BacktestTradePosition> positions = strategyPositions(state, strategyCode);
        if (trade == null || positions == null || positions.isEmpty()) {
            return PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        }

        List<BacktestTradePosition> openPositions = positions.stream()
                .filter(p -> "OPEN".equalsIgnoreCase(p.getStatus()))
                .toList();

        if (openPositions.isEmpty()) {
            return PositionSnapshot.builder()
                    .hasOpenPosition(false)
                    .build();
        }

        return backtestPositionSnapshotMapper.toSnapshot(trade, openPositions);
    }

    private int countOpenPositionsFor(BacktestState state, String strategyCode) {
        if (state == null) return 0;
        List<BacktestTradePosition> positions = strategyPositions(state, strategyCode);
        if (positions == null || positions.isEmpty()) return 0;
        return (int) positions.stream()
                .filter(p -> "OPEN".equalsIgnoreCase(p.getStatus()))
                .count();
    }

    private boolean hasOpenTradeFor(BacktestState state, String strategyCode) {
        if (state == null) return false;
        List<BacktestTradePosition> positions = strategyPositions(state, strategyCode);
        return positions != null
                && positions.stream().anyMatch(p -> "OPEN".equalsIgnoreCase(p.getStatus()));
    }

    /**
     * Listener-step gating only — "is there ANY open trade across the entire
     * cap that needs the strategy step to fire on this monitor tick?"
     */
    private boolean hasAnyOpenTrade(BacktestState state) {
        if (state == null) return false;
        Map<String, List<BacktestTradePosition>> byStrategy = state.getActiveTradePositionsByStrategy();
        if (byStrategy != null && !byStrategy.isEmpty()) {
            for (List<BacktestTradePosition> perStrategy : byStrategy.values()) {
                if (perStrategy == null) continue;
                for (BacktestTradePosition p : perStrategy) {
                    if ("OPEN".equalsIgnoreCase(p.getStatus())) return true;
                }
            }
            return false;
        }
        // Legacy mirror fallback (single-strategy runs).
        return state.getActiveTrade() != null
                && state.getActiveTradePositions() != null
                && state.getActiveTradePositions().stream().anyMatch(p -> "OPEN".equalsIgnoreCase(p.getStatus()));
    }

    private BacktestTrade strategyTrade(BacktestState state, String strategyCode) {
        if (state == null) return null;
        Map<String, BacktestTrade> byStrategy = state.getActiveTradesByStrategy();
        if (byStrategy != null && !byStrategy.isEmpty()) {
            // Multi-trade mode is active: per-strategy entries are
            // authoritative. Absence means "this strategy has no trade",
            // NOT "fall through to the legacy mirror" — the mirror points
            // at whichever trade was opened most recently and would leak
            // another strategy's position into this strategy's evaluation.
            // Use BacktestState's case-insensitive accessor so the lookup
            // matches the upper-cased keys written by addActiveTrade.
            return strategyCode == null ? null : state.getActiveTradeFor(strategyCode);
        }
        // No multi-trade entries — single-strategy / pre-B1 path. The
        // legacy mirror is the only source, and there's only one trade
        // possible, so the mirror is unambiguous.
        return state.getActiveTrade();
    }

    private List<BacktestTradePosition> strategyPositions(BacktestState state, String strategyCode) {
        if (state == null) return null;
        Map<String, List<BacktestTradePosition>> byStrategy = state.getActiveTradePositionsByStrategy();
        if (byStrategy != null && !byStrategy.isEmpty()) {
            // See strategyTrade — same rule applies to positions.
            if (strategyCode == null) return null;
            List<BacktestTradePosition> p = state.getActivePositionsFor(strategyCode);
            return p.isEmpty() ? null : p;
        }
        return state.getActiveTradePositions();
    }

    private String resolveStrategyLookupCode(BacktestRun backtestRun) {
        if (backtestRun.getStrategyCode() != null && !backtestRun.getStrategyCode().isBlank()) {
            return backtestRun.getStrategyCode();
        }
        return backtestRun.getStrategyName();
    }

    /**
     * Parses the (possibly comma-separated) strategyCode field into an ordered list.
     * "LSR_V2,VCB" → ["LSR_V2", "VCB"]
     * "LSR_V2"     → ["LSR_V2"]
     */
    private List<String> resolveStrategyCodeList(BacktestRun backtestRun) {
        String raw = resolveStrategyLookupCode(backtestRun);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("No strategy code could be resolved from BacktestRun");
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    /**
     * Resolves a {@link StrategyExecutorEntry} for every strategy code in the run,
     * preserving the declared order (= priority order for orchestrator fan-out).
     */
    private List<StrategyExecutorEntry> resolveStrategyExecutors(BacktestRun backtestRun) {
        return resolveStrategyCodeList(backtestRun).stream()
                .map(code -> new StrategyExecutorEntry(code, strategyExecutorFactory.get(code)))
                .toList();
    }

    /**
     * Merges multiple {@link StrategyRequirements} using OR-logic for boolean flags.
     * The first non-null bias interval is used.
     */
    private StrategyRequirements mergeRequirements(List<StrategyRequirements> list) {
        if (list == null || list.isEmpty()) {
            return StrategyRequirements.builder().build();
        }
        if (list.size() == 1) {
            return list.getFirst();
        }

        boolean biasTimeframe          = list.stream().anyMatch(r -> r != null && r.isRequireBiasTimeframe());
        boolean regimeSnapshot         = list.stream().anyMatch(r -> r != null && r.isRequireRegimeSnapshot());
        boolean volatilitySnapshot     = list.stream().anyMatch(r -> r != null && r.isRequireVolatilitySnapshot());
        boolean riskSnapshot           = list.stream().anyMatch(r -> r != null && r.isRequireRiskSnapshot());
        boolean marketQualitySnapshot  = list.stream().anyMatch(r -> r != null && r.isRequireMarketQualitySnapshot());
        boolean previousFeatureStore   = list.stream().anyMatch(r -> r != null && r.isRequirePreviousFeatureStore());

        String biasInterval = list.stream()
                .filter(r -> r != null && r.getBiasInterval() != null && !r.getBiasInterval().isBlank())
                .map(StrategyRequirements::getBiasInterval)
                .findFirst()
                .orElse(null);

        return StrategyRequirements.builder()
                .requireBiasTimeframe(biasTimeframe)
                .biasInterval(biasInterval)
                .requireRegimeSnapshot(regimeSnapshot)
                .requireVolatilitySnapshot(volatilitySnapshot)
                .requireRiskSnapshot(riskSnapshot)
                .requireMarketQualitySnapshot(marketQualitySnapshot)
                .requirePreviousFeatureStore(previousFeatureStore)
                .build();
    }

    private StrategyExecutorEntry findExecutorByCode(List<StrategyExecutorEntry> executors, String code) {
        if (code == null || executors == null) return null;
        return executors.stream()
                .filter(e -> code.equalsIgnoreCase(e.code()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Pairs a strategy code with its resolved executor. Package-private so
     * focused unit tests in the same package can exercise resolver helpers
     * without going through the full Spring context.
     */
    record StrategyExecutorEntry(String code, StrategyExecutor executor) {}

    private Boolean resolveAllowLong(BacktestRun backtestRun) {
        return backtestRun.getAllowLong() == null ? Boolean.TRUE : backtestRun.getAllowLong();
    }

    private Boolean resolveAllowShort(BacktestRun backtestRun) {
        return backtestRun.getAllowShort() == null ? Boolean.TRUE : backtestRun.getAllowShort();
    }

    private Integer resolveMaxOpenPositions(BacktestRun backtestRun) {
        return backtestRun.getMaxOpenPositions() == null ? 1 : backtestRun.getMaxOpenPositions();
    }

    private AccountStrategy buildSyntheticAccountStrategy(
            BacktestRun backtestRun, String strategyCode, String resolvedInterval,
            Map<String, BigDecimal> allocationByStrategy
    ) {
        UUID resolvedId = (backtestRun.getStrategyAccountStrategyIds() != null
                && backtestRun.getStrategyAccountStrategyIds().containsKey(strategyCode))
                ? backtestRun.getStrategyAccountStrategyIds().get(strategyCode)
                : backtestRun.getAccountStrategyId();

        // Phase B2 — strategies on a per-strategy interval need the synthetic
        // AccountStrategy to advertise THAT interval, not the run's primary.
        // Anything reading context.accountStrategy.intervalName for downstream
        // queries (FeatureStore lookups, regime maths, log labels) would
        // otherwise see the wrong timeframe. Falls back to the primary when
        // no per-strategy override was resolved.
        String interval = (resolvedInterval != null && !resolvedInterval.isBlank())
                ? resolvedInterval
                : backtestRun.getInterval();

        // Phase A — capital allocation pre-resolved at run start (see
        // resolveAllocationsForRun). Map-only lookup on the hot path.
        BigDecimal allocation = (allocationByStrategy != null && strategyCode != null
                && allocationByStrategy.containsKey(strategyCode))
                ? allocationByStrategy.get(strategyCode)
                : new BigDecimal("100");

        return AccountStrategy.builder()
                .accountStrategyId(resolvedId)
                .strategyCode(strategyCode)
                .intervalName(interval)
                .allowLong(resolveAllowLong(backtestRun))
                .allowShort(resolveAllowShort(backtestRun))
                .maxOpenPositions(resolveMaxOpenPositions(backtestRun))
                .enabled(Boolean.TRUE)
                .capitalAllocationPct(allocation)
                .build();
    }

    /**
     * Resolve every strategy's capital allocation ONCE at run start.
     * <p>
     * Lookup order per strategy:
     *   1. {@code backtestRun.strategyAllocations} (wizard override, run-scoped, must be > 0).
     *   2. The persistent {@code account_strategy.capital_allocation_pct} —
     *      <b>including explicit zero</b> (Fix G: a user-set 0 means "no
     *      capital for this strategy", not "fall through to default").
     *   3. {@code 100} (legacy default — only fires when neither layer set
     *      anything, preserving single-strategy run sizing).
     * <p>
     * Strategy code matching is case-insensitive against the canonicalised
     * uppercase keys persisted by {@code BacktestService.canonicaliseAllocations}.
     */
    /** Package-private for focused unit tests; see BacktestAllocationResolutionTest. */
    Map<String, BigDecimal> resolveAllocationsForRun(
            BacktestRun backtestRun, List<StrategyExecutorEntry> executors
    ) {
        Map<String, BigDecimal> wizardOverrides = backtestRun.getStrategyAllocations();
        Map<String, UUID> idMap = backtestRun.getStrategyAccountStrategyIds();
        Map<String, BigDecimal> out = new LinkedHashMap<>();

        for (StrategyExecutorEntry entry : executors) {
            String code = entry.code();
            BigDecimal value = null;

            if (wizardOverrides != null && code != null) {
                BigDecimal override = wizardOverrides.get(code.toUpperCase());
                if (override != null && override.signum() > 0) {
                    value = override;
                }
            }

            if (value == null) {
                UUID resolvedId = (idMap != null && code != null && idMap.containsKey(code))
                        ? idMap.get(code)
                        : backtestRun.getAccountStrategyId();
                if (resolvedId != null) {
                    AccountStrategy persisted = accountStrategyRepository.findById(resolvedId).orElse(null);
                    if (persisted != null && persisted.getCapitalAllocationPct() != null) {
                        // Fix G — respect explicit zero. Only fall through
                        // when the column is null (never set), not when the
                        // user deliberately set 0.
                        value = persisted.getCapitalAllocationPct();
                    }
                }
            }

            if (value == null) {
                value = new BigDecimal("100");
            }

            out.put(code, value);
            log.debug("Resolved capital allocation | strategy={} pct={}", code, value);
        }
        return out;
    }

    private void forceCloseRemainingOpenTrade(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData finalCandle
    ) {
        if (finalCandle == null) {
            return;
        }

        // Phase B1/B2 — flatten positions across ALL active trades so every
        // strategy's still-open legs get force-closed at end-of-backtest.
        // Falls back to the legacy single-slot iteration when no multi-trade
        // state has been registered (single-strategy runs).
        List<BacktestTradePosition> allPositions = new ArrayList<>();
        if (state.getActiveTradePositionsByStrategy() != null
                && !state.getActiveTradePositionsByStrategy().isEmpty()) {
            for (List<BacktestTradePosition> perStrategy
                    : state.getActiveTradePositionsByStrategy().values()) {
                if (perStrategy != null) allPositions.addAll(perStrategy);
            }
        } else if (state.getActiveTradePositions() != null) {
            allPositions.addAll(state.getActiveTradePositions());
        }
        if (allPositions.isEmpty()) {
            return;
        }

        // Snapshot to a fresh list — closeSinglePositionFromListener mutates
        // the live multi-trade map (removeActiveTrade) on the last leg, which
        // would ConcurrentModificationException a direct iteration.
        for (BacktestTradePosition position : new ArrayList<>(allPositions)) {
            if (!"OPEN".equalsIgnoreCase(position.getStatus())) {
                continue;
            }

            backtestTradeExecutorService.closeSinglePositionFromListener(
                    backtestRun,
                    state,
                    position,
                    finalCandle.getClosePrice(),
                    "FORCED_END_OF_BACKTEST_CLOSE",
                    finalCandle.getEndTime()
            );
        }
    }

    private BigDecimal resolveSyntheticAssetBalanceForBacktest(
            BacktestState state,
            MarketData strategyCandle
    ) {
        if (state == null || strategyCandle == null || strategyCandle.getClosePrice() == null) {
            return BigDecimal.ZERO;
        }

        if (state.getCashBalance() == null || state.getCashBalance().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        if (strategyCandle.getClosePrice().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return state.getCashBalance()
                .divide(strategyCandle.getClosePrice(), 12, RoundingMode.DOWN);
    }

    private void validateBacktestRun(BacktestRun backtestRun) {
        if (backtestRun == null) {
            throw new IllegalArgumentException("Backtest run must not be null");
        }

        if (backtestRun.getAsset() == null || backtestRun.getAsset().isBlank()) {
            throw new IllegalArgumentException("Backtest asset must not be blank");
        }

        if (backtestRun.getInterval() == null || backtestRun.getInterval().isBlank()) {
            throw new IllegalArgumentException("Backtest interval must not be blank");
        }

        if (backtestRun.getStartTime() == null || backtestRun.getEndTime() == null) {
            throw new IllegalArgumentException("Backtest startTime and endTime must not be null");
        }

        if (!backtestRun.getStartTime().isBefore(backtestRun.getEndTime())) {
            throw new IllegalArgumentException("Backtest startTime must be before endTime");
        }
    }

    /**
     * Updates trailingStopPrice to follow price in the favourable direction,
     * maintaining the fixed offset established between entryPrice and initialTrailingStopPrice.
     * Must be called after updatePriceExtremes so highestPriceSinceEntry / lowestPriceSinceEntry are current.
     */
    private void updateTrailingStop(BacktestTradePosition position) {
        if (position.getTrailingStopPrice() == null
                || position.getInitialTrailingStopPrice() == null
                || position.getEntryPrice() == null) {
            return;
        }

        if ("LONG".equalsIgnoreCase(position.getSide())) {
            BigDecimal highestPrice = position.getHighestPriceSinceEntry();
            if (highestPrice == null) {
                return;
            }
            BigDecimal offset = position.getEntryPrice().subtract(position.getInitialTrailingStopPrice());
            if (offset.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }
            BigDecimal newTrailing = highestPrice.subtract(offset);
            if (newTrailing.compareTo(position.getTrailingStopPrice()) > 0) {
                position.setTrailingStopPrice(newTrailing);
            }
        } else if ("SHORT".equalsIgnoreCase(position.getSide())) {
            BigDecimal lowestPrice = position.getLowestPriceSinceEntry();
            if (lowestPrice == null) {
                return;
            }
            BigDecimal offset = position.getInitialTrailingStopPrice().subtract(position.getEntryPrice());
            if (offset.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }
            BigDecimal newTrailing = lowestPrice.add(offset);
            if (newTrailing.compareTo(position.getTrailingStopPrice()) < 0) {
                position.setTrailingStopPrice(newTrailing);
            }
        }
    }

    private void updatePriceExtremes(BacktestTradePosition position, MarketData candle) {
        if ("LONG".equalsIgnoreCase(position.getSide())) {
            // Track both best (for trailing/BE) and worst (for MAE) intrabar prices
            if (candle.getHighPrice() != null
                    && (position.getHighestPriceSinceEntry() == null
                    || candle.getHighPrice().compareTo(position.getHighestPriceSinceEntry()) > 0)) {
                position.setHighestPriceSinceEntry(candle.getHighPrice());
            }
            if (candle.getLowPrice() != null
                    && (position.getLowestPriceSinceEntry() == null
                    || candle.getLowPrice().compareTo(position.getLowestPriceSinceEntry()) < 0)) {
                position.setLowestPriceSinceEntry(candle.getLowPrice());
            }
        } else if ("SHORT".equalsIgnoreCase(position.getSide())) {
            if (candle.getLowPrice() != null
                    && (position.getLowestPriceSinceEntry() == null
                    || candle.getLowPrice().compareTo(position.getLowestPriceSinceEntry()) < 0)) {
                position.setLowestPriceSinceEntry(candle.getLowPrice());
            }
            if (candle.getHighPrice() != null
                    && (position.getHighestPriceSinceEntry() == null
                    || candle.getHighPrice().compareTo(position.getHighestPriceSinceEntry()) > 0)) {
                position.setHighestPriceSinceEntry(candle.getHighPrice());
            }
        }
    }

    private record BiasData(
            List<MarketData> biasCandles,
            Map<LocalDateTime, FeatureStore> biasFeatureByStartTime
    ) {
    }
}