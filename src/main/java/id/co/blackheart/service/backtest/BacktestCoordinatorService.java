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
import id.co.blackheart.model.Account;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.risk.GateVerdict;
import id.co.blackheart.service.risk.RiskGuardService;
import id.co.blackheart.service.strategy.StrategyContextEnrichmentService;
import id.co.blackheart.service.strategy.StrategyExecutor;
import id.co.blackheart.service.strategy.StrategyExecutorFactory;
import id.co.blackheart.service.tradelistener.TradeListenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

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
    // V62 — gate evaluation for parity with the live path. Looked up at
    // entry time in tryFireEntry; null verdict from evaluate() means allow.
    private final RiskGuardService riskGuardService;
    private final AccountRepository accountRepository;

    /** Report progress at most every N candles. With MIN_INTERVAL_MS also
     *  throttling inside the tracker, this just cheaply avoids calling into
     *  it on every single tick. */
    private static final int PROGRESS_TICK_EVERY = 100;

    /**
     * Top-level backtest entry point. The method is long because it owns
     * three sequential phases — data load + validation, the candle loop,
     * and post-loop finalisation (force-close, equity, persistence). Each
     * phase needs the locals from the prior one, and breaking them up into
     * helpers either pushes 6+ values through a return record or allocates
     * mutable holders. Both shift the smell rather than fix it. The phases
     * are clearly delimited by section comments.
     */
    @SuppressWarnings("java:S138")
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

        if (CollectionUtils.isEmpty(monitorCandles)) {
            throw new IllegalArgumentException("No monitor market data found for interval: " + MONITOR_INTERVAL);
        }

        if (CollectionUtils.isEmpty(strategyCandles)) {
            throw new IllegalArgumentException("No strategy market data found for interval: " + strategyInterval);
        }

        if (CollectionUtils.isEmpty(strategyFeatures)) {
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

        // Per-strategy bias data — live parity. Each strategy's enrichment
        // (DefaultStrategyContextEnrichmentService) queries its OWN bias
        // interval; the prior code merged requirements and loaded a single
        // bias series, which fed wrong data to every strategy except the
        // one whose biasInterval was picked first by mergeRequirements.
        Map<String, BiasData> biasByStrategy = preloadBiasDataPerStrategy(backtestRun, executors);

        // Per-strategy interval contexts. When the run was submitted with a
        // strategyIntervals map, each strategy fires only on its own
        // timeframe's bar closes. Otherwise every strategy shares the primary
        // interval context.
        Map<String, IntervalContext> perStrategyContext = buildPerStrategyContexts(
                backtestRun, executors, strategyInterval,
                strategyCandleByEndTime, strategyFeatureByStartTime, sortedStrategyFeatures);

        // Resolve per-strategy sizing config ONCE at run start. Without this,
        // every monitor tick called accountStrategyRepository.findById for any
        // strategy lacking a wizard override — pathological N+1 on long
        // backtests (260k+ ticks × N strategies). V55 carries the risk-based
        // sizing toggle + riskPct alongside allocation so the synthetic
        // AccountStrategy mirrors the live row's sizing model.
        Map<String, StrategySizing> sizingByStrategy = resolveStrategySizingForRun(backtestRun, executors);

        final int totalCandles = monitorCandles.size();
        int processed = 0;
        for (MarketData monitorCandle : monitorCandles) {
            backtestTradeExecutorService.fillPendingEntry(
                    backtestRun, state, monitorCandle.getOpenPrice(), monitorCandle.getStartTime()
            );

            handleListenerStep(backtestRun, state, monitorCandle);

            // Live parity: strategy step ALWAYS runs after the listener
            // step. Live's WebSocket close events fire the strategy
            // independently — no cross-step gate prevents same-bar
            // re-entry after a stop/TP exit, and one strategy's close
            // never silences another strategy's signal.
            handleStrategyStep(
                    backtestRun,
                    state,
                    monitorCandle,
                    executors,
                    requirements,
                    biasByStrategy,
                    perStrategyContext,
                    sizingByStrategy
            );

            backtestStateService.checkIntraBarDrawdown(state, monitorCandle.getLowPrice());
            backtestStateService.checkIntraBarDrawdown(state, monitorCandle.getHighPrice());
            backtestStateService.updateEquityAndDrawdown(state, monitorCandle.getClosePrice());
            backtestEquityPointRecorder.recordPoint(state, backtestRun, monitorCandle);

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

    private void handleListenerStep(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData monitorCandle
    ) {
        // Flatten positions across ALL active trades so every open leg gets
        // stop-loss / TP checks regardless of which strategy owns it. Falls
        // back to the legacy single-slot iteration when no multi-trade state
        // has been registered.
        List<BacktestTradePosition> allActivePositions = collectAllActivePositions(state);
        if (allActivePositions.isEmpty()) {
            return;
        }

        for (BacktestTradePosition position : allActivePositions) {
            evaluateListenerForPosition(backtestRun, state, monitorCandle, position);
        }
    }

    private List<BacktestTradePosition> collectAllActivePositions(BacktestState state) {
        List<BacktestTradePosition> all = new ArrayList<>();
        if (state.getActiveTradePositionsByStrategy() != null
                && !CollectionUtils.isEmpty(state.getActiveTradePositionsByStrategy())) {
            for (List<BacktestTradePosition> perStrategy
                    : state.getActiveTradePositionsByStrategy().values()) {
                if (perStrategy != null) all.addAll(perStrategy);
            }
        } else if (state.getActiveTradePositions() != null) {
            all.addAll(state.getActiveTradePositions());
        }
        return all;
    }

    private void evaluateListenerForPosition(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData monitorCandle,
            BacktestTradePosition position
    ) {
        if (!"OPEN".equalsIgnoreCase(position.getStatus())) {
            return;
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
            return;
        }

        backtestTradeExecutorService.closeSinglePositionFromListener(
                backtestRun,
                state,
                position,
                listenerDecision.getExitPrice(),
                listenerDecision.getExitReason(),
                monitorCandle.getEndTime()
        );
    }

    /**
     * Multi-strategy orchestration on every monitor tick. Two phases run in
     * lock-step within one tick: existing owners manage their trades, then
     * strategies without a trade fan out entries gated by the per-interval
     * cap. Each per-strategy iteration is delegated to a helper so the
     * orchestrator method itself stays declarative.
     *
     * <p>Cap semantics match LiveOrchestratorCoordinatorService: at most one
     * active trade (or queued pending entry) per (account, interval) tuple.
     * Live enforces this implicitly by stopping fan-out at the first opener
     * within an interval-group; backtest enforces it via
     * {@link #intervalGroupBusy} in the entry loop. The legacy
     * {@code backtestRun.maxConcurrentStrategies} field is no longer
     * consulted — kept on the entity for backwards-compat with persisted
     * runs, but ineffective. Per-interval routing yields up to N concurrent
     * trades naturally (where N = number of distinct intervals in use),
     * matching live engine behaviour.
     */
    @SuppressWarnings("java:S107")
    private void handleStrategyStep(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData monitorCandle,
            List<StrategyExecutorEntry> executors,
            StrategyRequirements requirements,
            Map<String, BiasData> biasByStrategy,
            Map<String, IntervalContext> perStrategyContext,
            Map<String, StrategySizing> sizingByStrategy
    ) {
        if (executors.size() == 1) {
            handleSingleStrategyStep(backtestRun, state, monitorCandle,
                    executors.getFirst(), requirements,
                    biasByStrategy, perStrategyContext, sizingByStrategy);
            return;
        }

        // (1) Existing owners manage their trades — but ONLY when this
        // monitor tick aligns with the owner's strategy interval.
        Set<String> ownersAtTickStart = state.getActiveTradesByStrategy() == null
                ? Collections.emptySet()
                : new HashSet<>(state.getActiveTradesByStrategy().keySet());
        for (String ownerCode : ownersAtTickStart) {
            manageOwnerActiveTrade(backtestRun, state, monitorCandle, ownerCode,
                    executors, biasByStrategy, perStrategyContext, sizingByStrategy);
        }

        // (2) Strategies without a trade can fire entries — gated by the
        // per-interval-group cap (live parity) AND by whether their own
        // timeframe has a bar closing at this tick.
        for (StrategyExecutorEntry entry : executors) {
            tryFireEntry(backtestRun, state, monitorCandle, entry,
                    executors, biasByStrategy, perStrategyContext, sizingByStrategy);
        }
    }

    @SuppressWarnings("java:S107")
    private void manageOwnerActiveTrade(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData monitorCandle,
            String ownerCode,
            List<StrategyExecutorEntry> executors,
            Map<String, BiasData> biasByStrategy,
            Map<String, IntervalContext> perStrategyContext,
            Map<String, StrategySizing> sizingByStrategy
    ) {
        StrategyExecutorEntry ownerEntry = findExecutorByCode(executors, ownerCode);
        if (ownerEntry == null) return;
        IntervalContext ic = perStrategyContext.get(ownerCode);
        if (ic == null) return;
        MarketData strategyCandle = ic.candleByEndTime().get(monitorCandle.getEndTime());
        if (strategyCandle == null) return;  // owner's bar isn't closing yet
        FeatureStore strategyFeature = ic.featureByStartTime().get(strategyCandle.getStartTime());
        if (strategyFeature == null) return;

        PositionSnapshot ownerSnapshot = buildPositionSnapshotFor(state, ownerEntry.code());
        int ownerOpenCount = countOpenPositionsFor(state, ownerEntry.code());

        AccountStrategy syntheticAs = buildSyntheticAccountStrategy(
                backtestRun, ownerEntry.code(), ic.interval(), sizingByStrategy);
        StrategyRequirements ownerRequirements = ownerEntry.executor().getRequirements();
        BiasData ownerBias = biasFor(biasByStrategy, ownerEntry.code());
        EnrichedStrategyContext enrichedContext = buildAndEnrichContext(
                backtestRun, state, ic.interval(), strategyCandle, strategyFeature,
                ownerSnapshot, ownerOpenCount, true,
                buildExecutionMetadata(backtestRun, true),
                syntheticAs, ownerRequirements, ownerBias, ic.sortedFeatures(), monitorCandle);

        StrategyDecision decision = ownerEntry.executor().execute(enrichedContext);
        log.debug("Orchestrator active-trade | owner={} interval={} reason={}",
                ownerEntry.code(), ic.interval(), decision.getReason());
        // V62 — defensively gate any OPEN_* re-entry from a strategy that
        // already owns a trade. Live's applyEntryGates does the same. CLOSE_*
        // and UPDATE_* pass through unconditionally (per CLAUDE.md hard rule).
        if (isEntryDecision(decision)) {
            GateVerdict gateVerdict = evaluateBacktestGates(
                    backtestRun, state, syntheticAs, decision, strategyFeature);
            if (!gateVerdict.allowed()) {
                log.debug("Orchestrator active-trade entry BLOCKED by gate | owner={} reason={}",
                        ownerEntry.code(), gateVerdict.reason());
                return;
            }
        }
        backtestTradeExecutorService.execute(backtestRun, state, enrichedContext, decision);
    }

    @SuppressWarnings("java:S107")
    private void tryFireEntry(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData monitorCandle,
            StrategyExecutorEntry entry,
            List<StrategyExecutorEntry> executors,
            Map<String, BiasData> biasByStrategy,
            Map<String, IntervalContext> perStrategyContext,
            Map<String, StrategySizing> sizingByStrategy
    ) {
        // Skip strategies that already hold a trade or have a pending entry
        // queued — storePendingEntry would silently reject the latter anyway.
        if (state.hasActiveTradeFor(entry.code()) || state.hasPendingEntryFor(entry.code())) return;

        IntervalContext ic = perStrategyContext.get(entry.code());
        if (ic == null) return;

        // Per-interval-group cap (matches live's
        // LiveOrchestratorCoordinatorService.fanOutForEntry semantic):
        // first strategy in declaration order on this interval claims the
        // slot — subsequent strategies on the same interval are skipped.
        if (intervalGroupBusy(state, executors, ic.interval(), perStrategyContext)) {
            log.debug("Orchestrator entry skipped | strategy={} reason=interval-group-busy interval={}",
                    entry.code(), ic.interval());
            return;
        }

        MarketData strategyCandle = ic.candleByEndTime().get(monitorCandle.getEndTime());
        if (strategyCandle == null) return;
        FeatureStore strategyFeature = ic.featureByStartTime().get(strategyCandle.getStartTime());
        if (strategyFeature == null) return;

        // Entry path: this strategy has no trade by construction (filtered
        // above). Snapshot/count/hasOpenTrade reflect THIS strategy's empty
        // state, not "any strategy in the cap".
        PositionSnapshot entrySnapshot = PositionSnapshot.builder().hasOpenPosition(false).build();

        AccountStrategy syntheticAs = buildSyntheticAccountStrategy(
                backtestRun, entry.code(), ic.interval(), sizingByStrategy);
        StrategyRequirements entryRequirements = entry.executor().getRequirements();
        BiasData entryBias = biasFor(biasByStrategy, entry.code());
        EnrichedStrategyContext enrichedContext = buildAndEnrichContext(
                backtestRun, state, ic.interval(), strategyCandle, strategyFeature,
                entrySnapshot, 0, false,
                buildExecutionMetadata(backtestRun, false),
                syntheticAs, entryRequirements, entryBias,
                ic.sortedFeatures(), monitorCandle);

        StrategyDecision decision = entry.executor().execute(enrichedContext);
        if (!isEntryDecision(decision)) return;
        if (!StringUtils.hasText(decision.getStrategyCode())) {
            decision.setStrategyCode(entry.code());
        }

        // V62 — gate evaluation. Same gate stack live runs, applied to
        // backtest entries so backtest results reflect what live would
        // actually have admitted. All gates default off post-V62 backfill,
        // so this is a no-op until the operator opts in per strategy or
        // per run.
        GateVerdict gateVerdict = evaluateBacktestGates(
                backtestRun, state, syntheticAs, decision, strategyFeature);
        if (!gateVerdict.allowed()) {
            log.debug("Orchestrator entry BLOCKED by gate | strategy={} interval={} reason={}",
                    entry.code(), ic.interval(), gateVerdict.reason());
            return;
        }

        log.debug("Orchestrator entry | strategy={} interval={} side={}",
                entry.code(), ic.interval(), decision.getSide());
        backtestTradeExecutorService.execute(backtestRun, state, enrichedContext, decision);
    }

    /**
     * V62 — build EvaluationContext and call RiskGuardService.evaluate. Same
     * gate semantics as the live executor; gate toggles are read from the
     * synthetic AccountStrategy (which mirrors the bound persisted row's
     * per-gate enabled flags) plus per-run overrides from
     * {@code backtest_run.strategy_*_overrides}.
     *
     * <p>Returns {@code GateVerdict.allow()} when no Account can be resolved
     * (ad-hoc spec strategies with no bound account_strategy) — we cannot
     * evaluate account-level gates without an account, so we fall through
     * rather than block.
     */
    private GateVerdict evaluateBacktestGates(
            BacktestRun backtestRun, BacktestState state, AccountStrategy syntheticAs,
            StrategyDecision decision, FeatureStore strategyFeature
    ) {
        Account account = resolveBacktestAccount(state, syntheticAs);
        if (account == null) return GateVerdict.allow();

        String side = decision.getSide();
        Map<String, Boolean> overrides = resolveGateOverrides(backtestRun, decision.getStrategyCode());

        RiskGuardService.EvaluationContext ctx = new RiskGuardService.EvaluationContext(
                syntheticAs,
                account,
                side,
                strategyFeature,
                overrides,
                // accountId param is required by the ToLongBiFunction signature
                // (live needs it to scope the query) but unused here — backtest
                // state is single-account and the side string is the only
                // filter we need.
                (accountId, sideArg) -> state.countOpenTradesBySide(sideArg)
        );
        return riskGuardService.evaluate(ctx);
    }

    /**
     * V62 — resolve the Account behind a backtest's synthetic AccountStrategy.
     * Pulls account_id off the synthetic row (pre-resolved at run start via
     * {@code StrategySizing.accountId}), then reads through
     * {@link BacktestState#getAccountCache} so the lookup is a single DB hit
     * per (run, account) — the typical single-account backtest pays one query
     * across the whole run instead of one per entry decision. Returns
     * {@code null} for ad-hoc / unbound runs where no real account exists —
     * caller short-circuits to allow.
     */
    private Account resolveBacktestAccount(BacktestState state, AccountStrategy syntheticAs) {
        if (syntheticAs == null || syntheticAs.getAccountId() == null) return null;
        UUID accountId = syntheticAs.getAccountId();
        return state.getAccountCache().computeIfAbsent(
                accountId,
                id -> accountRepository.findByAccountId(id).orElse(null));
    }

    /**
     * V62 — build per-strategy gate-override map from the four backtest_run
     * JSONB columns. Returns {@code null} when no overrides apply, signalling
     * "use persisted per-strategy toggles only."
     */
    private Map<String, Boolean> resolveGateOverrides(BacktestRun run, String code) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        putIfPresent(out, RiskGuardService.GATE_KILL_SWITCH,
                resolveBoolOverride(run.getStrategyKillSwitchOverrides(), code));
        putIfPresent(out, RiskGuardService.GATE_REGIME,
                resolveBoolOverride(run.getStrategyRegimeOverrides(), code));
        putIfPresent(out, RiskGuardService.GATE_CORRELATION,
                resolveBoolOverride(run.getStrategyCorrelationOverrides(), code));
        putIfPresent(out, RiskGuardService.GATE_CONCURRENT_CAP,
                resolveBoolOverride(run.getStrategyConcurrentCapOverrides(), code));
        return out.isEmpty() ? null : out;
    }

    private static void putIfPresent(Map<String, Boolean> map, String key, Boolean v) {
        if (v != null) map.put(key, v);
    }

    /**
     * Single-strategy fast path. Extracted from {@link #handleStrategyStep} so
     * the multi-strategy orchestrator method stays under the size threshold;
     * behaviour is identical to the pre-extraction inline block.
     */
    @SuppressWarnings("java:S107")
    private void handleSingleStrategyStep(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData monitorCandle,
            StrategyExecutorEntry only,
            StrategyRequirements requirements,
            Map<String, BiasData> biasByStrategy,
            Map<String, IntervalContext> perStrategyContext,
            Map<String, StrategySizing> sizingByStrategy
    ) {
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

        AccountStrategy syntheticAs = buildSyntheticAccountStrategy(
                backtestRun, only.code(), ic.interval(), sizingByStrategy);
        BiasData onlyBias = biasFor(biasByStrategy, only.code());
        EnrichedStrategyContext enrichedContext = buildAndEnrichContext(
                backtestRun, state, ic.interval(), strategyCandle, strategyFeature,
                positionSnapshot, openPositionCount, strategyHasTrade,
                buildExecutionMetadata(backtestRun, strategyHasTrade),
                syntheticAs, requirements, onlyBias, ic.sortedFeatures(), monitorCandle);

        StrategyDecision decision = only.executor().execute(enrichedContext);
        log.debug("Strategy decision reason={}", decision.getReason());
        // V62 — gate entry decisions on the single-strategy fast path so the
        // gate stack runs uniformly regardless of how many strategies the
        // run carries. CLOSE_* / UPDATE_* pass through.
        if (isEntryDecision(decision)) {
            GateVerdict gateVerdict = evaluateBacktestGates(
                    backtestRun, state, syntheticAs, decision, strategyFeature);
            if (!gateVerdict.allowed()) {
                log.debug("Single-strategy entry BLOCKED by gate | strategy={} reason={}",
                        only.code(), gateVerdict.reason());
                return;
            }
        }
        backtestTradeExecutorService.execute(backtestRun, state, enrichedContext, decision);
    }

    /**
     * Per-strategy market data context. When the run has a single primary
     * interval, every strategy's IntervalContext points at the same maps
     * (no extra DB load). When {@code strategyIntervals} is set, each
     * unique interval gets its own load + maps.
     *
     * <p>Package-private for focused tests.
     */
    record IntervalContext(
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
                if (StringUtils.hasText(override)) resolved = override;
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

        // Fail loud when an interval has no candles. A strategy configured on
        // (e.g.) 30m with no 30m data in the window must throw, not silently
        // produce zero trades. Same applies to intervals coarser than the run
        // window (e.g. 1d strategy on a 6h backtest).
        if (CollectionUtils.isEmpty(candles)) {
            throw new IllegalArgumentException(
                    "No strategy market data found for symbol=" + backtestRun.getAsset()
                            + " interval=" + interval
                            + " between " + backtestRun.getStartTime()
                            + " and " + backtestRun.getEndTime()
                            + " — pick a different interval or extend the date range.");
        }
        if (CollectionUtils.isEmpty(features)) {
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
     *
     * <p>Suppression: this method is an internal aggregator across the
     * orchestrator's per-strategy state — every parameter is genuinely
     * needed and bundling them into a wrapper record would just shift the
     * param noise to the wrapper's constructor. Sonar S107 is a heuristic
     * for public APIs; this is a private helper.
     */
    @SuppressWarnings("java:S107")
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
                // V58 — read direction flags from the per-strategy synthetic
                // AS rather than re-deriving from the run-level scalar, so
                // the enrichment context stays consistent with the AS the
                // strategy will inspect.
                .allowLong(syntheticAs != null ? syntheticAs.getAllowLong() : resolveAllowLong(backtestRun))
                .allowShort(syntheticAs != null ? syntheticAs.getAllowShort() : resolveAllowShort(backtestRun))
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

    /**
     * Live-parity bias loading: each strategy's enrichment receives its OWN
     * bias data based on its own {@code requirements.biasInterval}. The
     * legacy mergeRequirements + single preloadBiasData path served the
     * same bias series to all strategies, which silently fed the wrong
     * data to every strategy except the first.
     * <p>
     * Loads are cached by biasInterval — two strategies that want the same
     * bias timeframe share a single DB query.
     *
     * <p>Package-private for focused tests.
     */
    Map<String, BiasData> preloadBiasDataPerStrategy(
            BacktestRun backtestRun, List<StrategyExecutorEntry> executors
    ) {
        Map<String, BiasData> byInterval = new HashMap<>();
        Map<String, BiasData> byStrategy = new LinkedHashMap<>();
        BiasData empty = new BiasData(List.of(), Map.of());

        for (StrategyExecutorEntry entry : executors) {
            StrategyRequirements req = entry.executor().getRequirements();
            if (req == null
                    || !req.isRequireBiasTimeframe()
                    || !StringUtils.hasText(req.getBiasInterval())) {
                byStrategy.put(entry.code(), empty);
                continue;
            }
            String biasInterval = req.getBiasInterval();
            BiasData loaded = byInterval.computeIfAbsent(biasInterval,
                    k -> loadBiasFor(backtestRun, k));
            byStrategy.put(entry.code(), loaded);
        }
        return byStrategy;
    }

    /** Look up a strategy's bias series; falls back to an empty one when missing. */
    private BiasData biasFor(Map<String, BiasData> biasByStrategy, String strategyCode) {
        if (biasByStrategy == null) return new BiasData(List.of(), Map.of());
        BiasData b = biasByStrategy.get(strategyCode);
        return b == null ? new BiasData(List.of(), Map.of()) : b;
    }

    private BiasData loadBiasFor(BacktestRun backtestRun, String biasInterval) {
        List<MarketData> biasCandles = marketDataRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(), biasInterval,
                backtestRun.getStartTime(), backtestRun.getEndTime());

        List<FeatureStore> biasFeatures = featureStoreRepository.findBySymbolIntervalAndRange(
                backtestRun.getAsset(), biasInterval,
                backtestRun.getStartTime(), backtestRun.getEndTime());

        Map<LocalDateTime, FeatureStore> biasFeatureByStartTime = biasFeatures == null
                ? Map.of()
                : biasFeatures.stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(
                                FeatureStore::getStartTime,
                                Function.identity(),
                                (existing, replacement) -> existing));

        List<MarketData> sortedBiasCandles = biasCandles == null
                ? List.of()
                : biasCandles.stream()
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(MarketData::getEndTime))
                        .toList();

        log.info("Loaded bias stream | symbol={} biasInterval={} candles={} features={}",
                backtestRun.getAsset(), biasInterval, sortedBiasCandles.size(),
                biasFeatures == null ? 0 : biasFeatures.size());
        return new BiasData(sortedBiasCandles, biasFeatureByStartTime);
    }

    private FeatureStore resolvePreviousFeatureStore(
            List<FeatureStore> sortedFeatures,
            LocalDateTime currentStartTime
    ) {
        if (CollectionUtils.isEmpty(sortedFeatures) || currentStartTime == null) {
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
        if (CollectionUtils.isEmpty(biasCandles) || currentTime == null) {
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
        Map<String, Object> meta = HashMap.newHashMap(3);
        meta.put("source", EXECUTION_SOURCE);
        meta.put("backtestRunId", backtestRun.getBacktestRunId());
        meta.put("hasOpenTrade", hasOpenTrade);
        return meta;
    }

    /**
     * Multi-trade-aware snapshot. Each strategy sees ONLY its own positions,
     * so {@code context.hasOpenPosition} reflects the calling strategy's
     * state, not whichever trade was most recently opened across the cap.
     * Falls back to the single-trade mirror when the per-strategy map is empty.
     */
    private PositionSnapshot buildPositionSnapshotFor(BacktestState state, String strategyCode) {
        BacktestTrade trade = strategyTrade(state, strategyCode);
        List<BacktestTradePosition> positions = strategyPositions(state, strategyCode);
        if (trade == null || CollectionUtils.isEmpty(positions)) {
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
        if (CollectionUtils.isEmpty(positions)) return 0;
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

    private BacktestTrade strategyTrade(BacktestState state, String strategyCode) {
        if (state == null) return null;
        Map<String, BacktestTrade> byStrategy = state.getActiveTradesByStrategy();
        if (!CollectionUtils.isEmpty(byStrategy)) {
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
        if (state == null) return List.of();
        Map<String, List<BacktestTradePosition>> byStrategy = state.getActiveTradePositionsByStrategy();
        if (!CollectionUtils.isEmpty(byStrategy)) {
            // See strategyTrade — same rule applies to positions.
            if (strategyCode == null) return List.of();
            return state.getActivePositionsFor(strategyCode);
        }
        List<BacktestTradePosition> legacy = state.getActiveTradePositions();
        return legacy == null ? List.of() : legacy;
    }

    private String resolveStrategyLookupCode(BacktestRun backtestRun) {
        if (StringUtils.hasText(backtestRun.getStrategyCode())) {
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
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("No strategy code could be resolved from BacktestRun");
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * Resolves a {@link StrategyExecutorEntry} for every strategy code in the
     * run, sorted ascending by {@code AccountStrategy.priorityOrder} —
     * matching live's {@code LiveOrchestratorCoordinatorService.process}
     * ordering. Strategies without a resolvable persistent AS (or with no
     * priority value) sort to the end with a neutral default; ties fall
     * back to declaration order via {@link Comparator#thenComparingInt}.
     *
     * <p>Package-private for focused tests.
     */
    List<StrategyExecutorEntry> resolveStrategyExecutors(BacktestRun backtestRun) {
        List<String> codes = resolveStrategyCodeList(backtestRun);
        Map<String, Integer> priorityByCode = resolvePriorityOrders(backtestRun, codes);

        // Stable sort: priorityOrder asc, declaration index asc on tie.
        return java.util.stream.IntStream.range(0, codes.size())
                .mapToObj(idx -> {
                    String code = codes.get(idx);
                    return new IndexedExecutor(
                            idx,
                            priorityByCode.getOrDefault(code, Integer.MAX_VALUE),
                            new StrategyExecutorEntry(code, strategyExecutorFactory.get(code))
                    );
                })
                .sorted(Comparator.<IndexedExecutor>comparingInt(e -> e.priority)
                        .thenComparingInt(e -> e.declarationIndex))
                .map(e -> e.entry)
                .toList();
    }

    /** Carrier used only inside {@link #resolveStrategyExecutors}'s sort. */
    private record IndexedExecutor(int declarationIndex, int priority, StrategyExecutorEntry entry) {}

    /**
     * Look up each strategy's persistent {@code AccountStrategy.priorityOrder}.
     * Caches the lookup (resolveAllocationsForRun also queries findById, so
     * if/when the two paths get unified, this becomes a free lookup).
     * Strategies with no resolvable AS or null priority map to MAX_VALUE
     * (sort to end).
     *
     * <p>Package-private for focused tests.
     */
    Map<String, Integer> resolvePriorityOrders(BacktestRun backtestRun, List<String> codes) {
        Map<String, Integer> out = new HashMap<>();
        Map<String, UUID> idMap = backtestRun.getStrategyAccountStrategyIds();

        for (String code : codes) {
            UUID resolvedId = (idMap != null && code != null && idMap.containsKey(code))
                    ? idMap.get(code)
                    : backtestRun.getAccountStrategyId();
            if (resolvedId == null) {
                out.put(code, Integer.MAX_VALUE);
                continue;
            }
            AccountStrategy persisted = accountStrategyRepository.findById(resolvedId).orElse(null);
            if (persisted != null && persisted.getPriorityOrder() != null) {
                out.put(code, persisted.getPriorityOrder());
            } else {
                out.put(code, Integer.MAX_VALUE);
            }
        }
        return out;
    }

    /**
     * Merges multiple {@link StrategyRequirements} using OR-logic for boolean flags.
     * The first non-null bias interval is used.
     */
    private StrategyRequirements mergeRequirements(List<StrategyRequirements> list) {
        if (CollectionUtils.isEmpty(list)) {
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
                .filter(r -> r != null && StringUtils.hasText(r.getBiasInterval()))
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
     * Live-parity per-interval-group cap. Returns true when any strategy on
     * the given interval already holds an active trade or a queued pending
     * entry — at which point no other strategy on that interval may open
     * a new trade until the slot frees up. Mirrors
     * {@link id.co.blackheart.service.live.LiveOrchestratorCoordinatorService}'s
     * fan-out-stops-at-first-opener pattern.
     *
     * <p>Package-private for focused tests.
     */
    boolean intervalGroupBusy(
            BacktestState state,
            List<StrategyExecutorEntry> executors,
            String interval,
            Map<String, IntervalContext> perStrategyContext
    ) {
        if (interval == null) return false;
        for (StrategyExecutorEntry entry : executors) {
            IntervalContext ic = perStrategyContext.get(entry.code());
            if (ic == null || !interval.equals(ic.interval())) continue;
            if (state.hasActiveTradeFor(entry.code()) || state.hasPendingEntryFor(entry.code())) {
                return true;
            }
        }
        return false;
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
            Map<String, StrategySizing> sizingByStrategy
    ) {
        UUID resolvedId = (backtestRun.getStrategyAccountStrategyIds() != null
                && backtestRun.getStrategyAccountStrategyIds().containsKey(strategyCode))
                ? backtestRun.getStrategyAccountStrategyIds().get(strategyCode)
                : backtestRun.getAccountStrategyId();

        // The synthetic AccountStrategy must advertise the strategy's own
        // interval, not the run's primary. Downstream queries (FeatureStore
        // lookups, regime maths, log labels) read intervalName from
        // context.accountStrategy — wrong timeframe here means wrong data.
        // Falls back to the primary when no per-strategy override is set.
        String interval = StringUtils.hasText(resolvedInterval)
                ? resolvedInterval
                : backtestRun.getInterval();

        // Sizing config pre-resolved at run start (see
        // resolveStrategySizingForRun) — map-only lookup on the hot path.
        // V55 carries the risk-based-sizing toggle + riskPct alongside
        // allocation so the synthetic AS mirrors the live row's sizing
        // model. Falls back to legacy 100% allocation + risk-based OFF when
        // no entry exists (single-strategy legacy runs etc).
        StrategySizing sizing = (sizingByStrategy != null && strategyCode != null
                && sizingByStrategy.containsKey(strategyCode))
                ? sizingByStrategy.get(strategyCode)
                : StrategySizing.legacy(new BigDecimal("100"));

        return AccountStrategy.builder()
                .accountStrategyId(resolvedId)
                // V62 review-fix #3 — accountId pre-resolved at run start
                // (from the bound persisted row). Lets the backtest gate path
                // skip a per-call accountStrategyRepository lookup.
                .accountId(sizing.accountId())
                .strategyCode(strategyCode)
                .intervalName(interval)
                // V58 — direction flags now travel through StrategySizing,
                // sourced per-strategy from the bound account_strategy (or
                // the run-level fallback for ad-hoc spec strategies).
                .allowLong(sizing.allowLong())
                .allowShort(sizing.allowShort())
                .maxOpenPositions(resolveMaxOpenPositions(backtestRun))
                .enabled(Boolean.TRUE)
                .capitalAllocationPct(sizing.allocationPct())
                .useRiskBasedSizing(sizing.useRiskBasedSizing())
                .riskPct(sizing.riskPct())
                // V62 — gate toggles mirrored from the persisted row so
                // RiskGuardService.evaluate sees the same state live would.
                // Per-run overrides (from backtest_run.strategy_*_overrides)
                // are applied separately via EvaluationContext.gateOverrides.
                .killSwitchGateEnabled(sizing.killSwitchGateEnabled())
                .regimeGateEnabled(sizing.regimeGateEnabled())
                .correlationGateEnabled(sizing.correlationGateEnabled())
                .concurrentCapGateEnabled(sizing.concurrentCapGateEnabled())
                // V62 review-fix #1 — kill-switch runtime state on the
                // synthetic AS so the gate (when enabled) denies entries
                // for a currently-tripped strategy in backtest, matching live.
                .isKillSwitchTripped(
                        sizing.isKillSwitchTripped() != null ? sizing.isKillSwitchTripped() : Boolean.FALSE)
                .killSwitchReason(sizing.killSwitchReason())
                .build();
    }

    /**
     * V55 — pre-resolved sizing config for one strategy in a backtest run.
     * Bundles allocation (subject to wizard overrides) plus the risk-based
     * sizing toggle and per-trade risk fraction (always read from the live
     * row — wizard doesn't override these today). Plumbed end-to-end to
     * {@link #buildSyntheticAccountStrategy} so the synthetic
     * {@link AccountStrategy} the engine sees during backtest matches the
     * sizing config of the live row that the operator is testing.
     */
    record StrategySizing(
            BigDecimal allocationPct,
            Boolean useRiskBasedSizing,
            BigDecimal riskPct,
            Boolean allowLong,
            Boolean allowShort,
            // V62 — gate toggles resolved at run start so the synthetic
            // AccountStrategy carries the persisted row's gate state without
            // a per-bar DB lookup. All four default false matching the V62
            // backfill — a strategy with no persisted row gets every gate
            // off, so the backtest applies no gates.
            Boolean killSwitchGateEnabled,
            Boolean regimeGateEnabled,
            Boolean correlationGateEnabled,
            Boolean concurrentCapGateEnabled,
            // V62 review-fix #1 — kill-switch runtime state propagated from
            // the bound persisted row. Backtest's kill-switch gate (when
            // enabled) honours this so a currently-tripped live strategy
            // produces zero trades in a parity backtest. Null/false when
            // unbound.
            Boolean isKillSwitchTripped,
            String killSwitchReason,
            // V62 review-fix #3 — pre-resolved accountId so the backtest
            // gate evaluator looks up Account from the BacktestState cache
            // instead of issuing an accountStrategyRepository.findById +
            // accountRepository.findByAccountId pair per entry decision.
            UUID accountId
    ) {
        static StrategySizing legacy(BigDecimal alloc) {
            // Used when no persisted row backs the run (synthetic / pre-V55
            // defaults). Risk-based path stays OFF so legacy behaviour is
            // preserved when the operator hasn't opted in. Direction flags
            // default permissive — the run-level allowLong/allowShort still
            // act as the override layer for ad-hoc spec strategies that
            // don't pin an account_strategy. Gate toggles default false so
            // an unbound run never accidentally enforces gates it can't
            // evaluate against a real account.
            return new StrategySizing(alloc, Boolean.FALSE, new BigDecimal("0.0500"),
                    Boolean.TRUE, Boolean.TRUE,
                    Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
                    Boolean.FALSE, null, null);
        }
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
     *
     * <p>Package-private wrapper preserved for {@link
     * BacktestAllocationResolutionTest}; production callers use
     * {@link #resolveStrategySizingForRun} so the risk-sizing toggle / riskPct
     * round-trip into the synthetic AccountStrategy.
     */
    Map<String, BigDecimal> resolveAllocationsForRun(
            BacktestRun backtestRun, List<StrategyExecutorEntry> executors
    ) {
        Map<String, StrategySizing> full = resolveStrategySizingForRun(backtestRun, executors);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map.Entry<String, StrategySizing> e : full.entrySet()) {
            out.put(e.getKey(), e.getValue().allocationPct());
        }
        return out;
    }

    /**
     * V55 — full per-strategy sizing config. Same lookup discipline as
     * {@link #resolveAllocationsForRun} for allocation; additionally pulls
     * {@code useRiskBasedSizing} + {@code riskPct} from the persisted row so
     * the synthetic backtest {@link AccountStrategy} mirrors the live row's
     * sizing model. When no persisted row resolves (single-strategy legacy
     * runs etc), falls back to {@link StrategySizing#legacy(BigDecimal)} —
     * risk-based OFF, matching pre-V55 behaviour.
     */
    Map<String, StrategySizing> resolveStrategySizingForRun(
            BacktestRun backtestRun, List<StrategyExecutorEntry> executors
    ) {
        Map<String, BigDecimal> wizardOverrides = backtestRun.getStrategyAllocations();
        Map<String, BigDecimal> wizardRiskOverrides = backtestRun.getStrategyRiskPcts();
        // V58 — per-strategy direction overrides. Same lookup discipline as
        // riskOverrides: wizard map wins when the key is present, otherwise
        // fall through to the bound account_strategy's flag.
        Map<String, Boolean> wizardAllowLongOverrides = backtestRun.getStrategyAllowLong();
        Map<String, Boolean> wizardAllowShortOverrides = backtestRun.getStrategyAllowShort();
        Map<String, UUID> idMap = backtestRun.getStrategyAccountStrategyIds();
        Map<String, StrategySizing> out = new LinkedHashMap<>();

        for (StrategyExecutorEntry entry : executors) {
            String code = entry.code();
            BigDecimal override = resolveWizardOverride(wizardOverrides, code);
            BigDecimal riskOverride = resolveRiskOverride(wizardRiskOverrides, code);
            Boolean allowLongOverride = resolveBoolOverride(wizardAllowLongOverrides, code);
            Boolean allowShortOverride = resolveBoolOverride(wizardAllowShortOverrides, code);
            AccountStrategy persisted = resolvePersistedAccountStrategy(idMap, code, backtestRun);

            BigDecimal allocation = resolveAllocationPct(override, persisted);
            Boolean allowLong = resolveEffectiveAllowLong(persisted, allowLongOverride, backtestRun);
            Boolean allowShort = resolveEffectiveAllowShort(persisted, allowShortOverride, backtestRun);
            StrategySizing sizing = buildSizingRecord(allocation, riskOverride, persisted, allowLong, allowShort);
            out.put(code, sizing);
            log.debug("Resolved sizing | strategy={} pct={} useRisk={} riskPct={} allowLong={} allowShort={}",
                    code, sizing.allocationPct(),
                    sizing.useRiskBasedSizing(), sizing.riskPct(),
                    sizing.allowLong(), sizing.allowShort());
        }
        return out;
    }

    private BigDecimal resolveAllocationPct(BigDecimal override, AccountStrategy persisted) {
        if (override != null) return override;
        // Fix G — respect explicit zero from the persisted row.
        if (persisted != null && persisted.getCapitalAllocationPct() != null) {
            return persisted.getCapitalAllocationPct();
        }
        return new BigDecimal("100");
    }

    private static Boolean defaultTrue(Boolean flag) {
        return flag != null ? flag : Boolean.TRUE;
    }

    // Direction flags precedence: (1) wizard per-strategy override (V58 strategyAllowLong /
    // strategyAllowShort), (2) bound account_strategy, (3) run-level flag (permissive
    // fallback for ad-hoc specs that don't pin an account_strategy).
    private Boolean resolveEffectiveAllowLong(AccountStrategy persisted, Boolean override, BacktestRun run) {
        Boolean base = persisted != null ? defaultTrue(persisted.getAllowLong()) : resolveAllowLong(run);
        return override != null ? override : base;
    }

    private Boolean resolveEffectiveAllowShort(AccountStrategy persisted, Boolean override, BacktestRun run) {
        Boolean base = persisted != null ? defaultTrue(persisted.getAllowShort()) : resolveAllowShort(run);
        return override != null ? override : base;
    }

    private StrategySizing buildSizingRecord(BigDecimal allocation, BigDecimal riskOverride,
            AccountStrategy persisted, Boolean allowLong, Boolean allowShort) {
        if (persisted == null) {
            // No persisted row resolved. A wizard riskOverride is explicit operator
            // intent — honour it even without a persisted anchor so it isn't dropped
            // on ad-hoc runs that don't pin an account_strategy. Gate toggles
            // default off (StrategySizing.legacy would also produce this).
            if (riskOverride != null) {
                return new StrategySizing(allocation, Boolean.TRUE, riskOverride, allowLong, allowShort,
                        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
                        Boolean.FALSE, null, null);
            }
            return StrategySizing.legacy(allocation);
        }
        Boolean useRisk = persisted.getUseRiskBasedSizing();
        BigDecimal riskPct;
        // V57 — wizard-level riskPct override wins and implicitly forces risk-based sizing on.
        if (riskOverride != null) {
            useRisk = Boolean.TRUE;
            riskPct = riskOverride;
        } else {
            riskPct = persisted.getRiskPct() != null ? persisted.getRiskPct() : new BigDecimal("0.0500");
        }
        // V62 — copy gate toggles from the persisted row so the synthetic
        // AccountStrategy the engine sees during backtest mirrors live's gate
        // state. nullSafe()'d to FALSE because the column is NOT NULL with
        // default false (post-V62 backfill); paranoia for any pre-V62 caller
        // that constructs an AccountStrategy without the field set.
        // V62 review-fix #1 — also propagate kill-switch RUNTIME state
        // (tripped flag + reason) so an enabled kill-switch gate in backtest
        // produces parity behaviour: a currently-tripped live strategy
        // refuses to open trades in its backtest too.
        return new StrategySizing(
                allocation, useRisk != null ? useRisk : Boolean.FALSE, riskPct, allowLong, allowShort,
                Boolean.TRUE.equals(persisted.getKillSwitchGateEnabled()),
                Boolean.TRUE.equals(persisted.getRegimeGateEnabled()),
                Boolean.TRUE.equals(persisted.getCorrelationGateEnabled()),
                Boolean.TRUE.equals(persisted.getConcurrentCapGateEnabled()),
                Boolean.TRUE.equals(persisted.getIsKillSwitchTripped()),
                persisted.getKillSwitchReason(),
                persisted.getAccountId());
    }

    /**
     * V57 — wizard-level per-strategy riskPct override lookup. Mirrors
     * {@link #resolveWizardOverride} for allocation: case-insensitive key
     * match, drop non-positive values. Out-of-range values are filtered by
     * {@code BacktestService.canonicaliseStrategyRiskPcts} upstream so we
     * only see (0, 0.20] here.
     */
    private BigDecimal resolveRiskOverride(Map<String, BigDecimal> wizardOverrides, String code) {
        return resolveWizardOverride(wizardOverrides, code);
    }

    private BigDecimal resolveWizardOverride(Map<String, BigDecimal> wizardOverrides, String code) {
        if (wizardOverrides == null || code == null) return null;
        BigDecimal override = wizardOverrides.get(code.toUpperCase());
        return (override != null && override.signum() > 0) ? override : null;
    }

    /**
     * V58 — case-insensitive lookup for the per-strategy direction override
     * maps. Returns {@code null} when the map doesn't carry a value for
     * this strategy, signaling "no override → use the persisted AS flag".
     * Boolean.FALSE is a valid override value (force direction off).
     */
    // 3-state Boolean: null = no override (use persisted AS flag), TRUE = force
    // on, FALSE = force off. The null sentinel is load-bearing — collapsing it
    // to false would silently override every strategy not in the map.
    @SuppressWarnings("java:S2447")
    private Boolean resolveBoolOverride(Map<String, Boolean> wizardOverrides, String code) {
        if (wizardOverrides == null || code == null) return null;
        return wizardOverrides.get(code.toUpperCase());
    }

    private AccountStrategy resolvePersistedAccountStrategy(
            Map<String, UUID> idMap, String code, BacktestRun backtestRun
    ) {
        UUID resolvedId = (idMap != null && code != null && idMap.containsKey(code))
                ? idMap.get(code)
                : backtestRun.getAccountStrategyId();
        if (resolvedId == null) return null;
        return accountStrategyRepository.findById(resolvedId).orElse(null);
    }

    private void forceCloseRemainingOpenTrade(
            BacktestRun backtestRun,
            BacktestState state,
            MarketData finalCandle
    ) {
        if (finalCandle == null) {
            return;
        }

        // Flatten positions across ALL active trades so every strategy's
        // still-open legs get force-closed at end-of-backtest. Falls back to
        // the single-slot iteration when no multi-trade state is registered.
        List<BacktestTradePosition> allPositions = new ArrayList<>();
        if (state.getActiveTradePositionsByStrategy() != null
                && !CollectionUtils.isEmpty(state.getActiveTradePositionsByStrategy())) {
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

        if (!StringUtils.hasText(backtestRun.getAsset())) {
            throw new IllegalArgumentException("Backtest asset must not be blank");
        }

        if (!StringUtils.hasText(backtestRun.getInterval())) {
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
            updateLongTrailingStop(position);
        } else if ("SHORT".equalsIgnoreCase(position.getSide())) {
            updateShortTrailingStop(position);
        }
    }

    private void updateLongTrailingStop(BacktestTradePosition position) {
        BigDecimal highestPrice = position.getHighestPriceSinceEntry();
        if (highestPrice == null) return;
        BigDecimal offset = position.getEntryPrice().subtract(position.getInitialTrailingStopPrice());
        if (offset.compareTo(BigDecimal.ZERO) <= 0) return;
        BigDecimal newTrailing = highestPrice.subtract(offset);
        if (newTrailing.compareTo(position.getTrailingStopPrice()) > 0) {
            position.setTrailingStopPrice(newTrailing);
        }
    }

    private void updateShortTrailingStop(BacktestTradePosition position) {
        BigDecimal lowestPrice = position.getLowestPriceSinceEntry();
        if (lowestPrice == null) return;
        BigDecimal offset = position.getInitialTrailingStopPrice().subtract(position.getEntryPrice());
        if (offset.compareTo(BigDecimal.ZERO) <= 0) return;
        BigDecimal newTrailing = lowestPrice.add(offset);
        if (newTrailing.compareTo(position.getTrailingStopPrice()) < 0) {
            position.setTrailingStopPrice(newTrailing);
        }
    }

    private void updatePriceExtremes(BacktestTradePosition position, MarketData candle) {
        // LONG and SHORT both track best + worst intrabar prices (best for
        // trailing/BE, worst for MAE) — branching only guards against
        // mis-labelled positions where side ≠ LONG/SHORT.
        if (!"LONG".equalsIgnoreCase(position.getSide())
                && !"SHORT".equalsIgnoreCase(position.getSide())) {
            return;
        }
        updateHighestPrice(position, candle.getHighPrice());
        updateLowestPrice(position, candle.getLowPrice());
    }

    private void updateHighestPrice(BacktestTradePosition position, BigDecimal price) {
        if (price != null
                && (position.getHighestPriceSinceEntry() == null
                || price.compareTo(position.getHighestPriceSinceEntry()) > 0)) {
            position.setHighestPriceSinceEntry(price);
        }
    }

    private void updateLowestPrice(BacktestTradePosition position, BigDecimal price) {
        if (price != null
                && (position.getLowestPriceSinceEntry() == null
                || price.compareTo(position.getLowestPriceSinceEntry()) < 0)) {
            position.setLowestPriceSinceEntry(price);
        }
    }

    /** Package-private for focused tests. */
    record BiasData(
            List<MarketData> biasCandles,
            Map<LocalDateTime, FeatureStore> biasFeatureByStartTime
    ) {
    }
}