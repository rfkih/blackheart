package id.co.blackheart.dto.backtest;

import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.BacktestEquityPoint;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.model.BacktestTradePosition;
import id.co.blackheart.model.FeatureStore;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Data
@Builder
public class BacktestState {

    private BigDecimal cashBalance;

    /**
     * Synthetic equity tracking fields.
     */
    private BigDecimal currentEquity;
    private BigDecimal peakEquity;
    private BigDecimal maxDrawdownPercent;

    /**
     * Current active parent trade — legacy single-trade slot. Kept for
     * backward compatibility with the 36+ call sites that read this
     * directly. New callers should prefer the multi-trade APIs:
     * {@link #countActiveTrades()}, {@link #addActiveTrade},
     * {@link #removeActiveTrade}.
     *
     * <p>Invariant: when {@link #activeTradesByStrategy} has entries,
     * {@code activeTrade} mirrors the most recently opened trade so
     * legacy code paths see <i>some</i> active trade rather than null.
     * Both APIs stay consistent across all mutations.
     */
    private BacktestTrade activeTrade;

    /**
     * Active child positions for {@link #activeTrade}. Mirrors
     * {@link #activeTradePositionsByStrategy} for the active-trade slot.
     */
    private List<BacktestTradePosition> activeTradePositions;

    /**
     * Multi-trade state. Map of strategy code → that strategy's open parent
     * trade. Empty when no strategies have positions; size is compared against
     * {@code BacktestRun.maxConcurrentStrategies} at entry-gate time.
     *
     * <p>One slot per strategy code: when a strategy already owns an open
     * trade, the orchestrator routes follow-up signals only to that executor.
     * Multi-trade means different strategies hold different trades
     * concurrently — not one strategy holding N trades.
     *
     * <p>Backed by {@link java.util.LinkedHashMap} (insertion order) so the
     * cap-aware fan-out and legacy-mirror refresh both iterate consistently.
     * HashMap order is undefined; bit-identical outputs across runs require
     * stable iteration.
     */
    @Builder.Default
    private java.util.Map<String, BacktestTrade> activeTradesByStrategy = new java.util.LinkedHashMap<>();

    /**
     * Per-strategy active child positions. Same key shape as
     * {@link #activeTradesByStrategy}.
     */
    @Builder.Default
    private java.util.Map<String, List<BacktestTradePosition>> activeTradePositionsByStrategy = new java.util.LinkedHashMap<>();

    /**
     * Completed parent trades.
     */
    private List<BacktestTrade> completedTrades;

    /**
     * Completed child positions.
     */
    private List<BacktestTradePosition> completedTradePositions;

    private List<BacktestEquityPoint> equityPoints;

    /**
     * TreeMap index for O(log n) equity point upsert and previous-day lookup during backtest execution.
     * Converted to equityPoints list at the end of the backtest run.
     */
    @Builder.Default
    private TreeMap<LocalDate, BacktestEquityPoint> equityPointIndex = new TreeMap<>();

    /**
     * Legacy single-slot deferred entry order. Mirror of whichever strategy
     * most recently queued a pending entry — preserved so external code that
     * still reads {@code pendingEntry} sees something. New callers should use
     * {@link #pendingEntriesByStrategy} via the per-strategy accessors below.
     */
    private PendingEntry pendingEntry;

    /**
     * Per-strategy pending entries. With multiple strategies in one run, two
     * can signal entries on the same monitor bar; each gets its own slot so
     * neither is silently dropped. Filled on the next bar's open price
     * (look-ahead-free) by {@code BacktestTradeExecutorService.fillPendingEntry}.
     */
    @Builder.Default
    private java.util.Map<String, PendingEntry> pendingEntriesByStrategy = new java.util.LinkedHashMap<>();

    /**
     * V62 — per-run cache of {@link Account} keyed by accountId. Populated
     * lazily on first lookup by the backtest gate evaluator so the gate
     * stack doesn't hit the DB on every entry decision. Single-entry for
     * the typical single-account run; multi-entry only when a multi-strategy
     * run spans different accounts.
     */
    @Builder.Default
    private Map<UUID, Account> accountCache = new HashMap<>();

    /**
     * Deferred entry order. {@code interval} carries the strategy's resolved
     * timeframe so the trade row gets stamped correctly — null/blank falls
     * back to the run-level interval.
     */
    public record PendingEntry(
            StrategyDecision decision,
            String side,
            FeatureStore featureStore,
            FeatureStore biasFeatureStore,
            String interval
    ) {}

    public static BacktestState initial(BacktestRun run) {
        return BacktestState.builder()
                .cashBalance(run.getInitialCapital())
                .currentEquity(run.getInitialCapital())
                .peakEquity(run.getInitialCapital())
                .maxDrawdownPercent(BigDecimal.ZERO)
                .activeTrade(null)
                .activeTradePositions(new ArrayList<>())
                .activeTradesByStrategy(new java.util.LinkedHashMap<>())
                .activeTradePositionsByStrategy(new java.util.LinkedHashMap<>())
                .completedTrades(new ArrayList<>())
                .completedTradePositions(new ArrayList<>())
                .equityPoints(new ArrayList<>())
                .accountCache(new HashMap<>())
                .build();
    }

    // ── Multi-trade API ──────────────────────────────────────────────────

    /**
     * Canonical key form for the multi-trade maps. Strategy codes flow in
     * from several sources (executor entries, decision objects, the
     * BacktestRun's strategyCode field) with no shared casing convention,
     * so we normalise to upper-case at every API boundary. Matches
     * {@code findExecutorByCode}'s case-insensitive comparison.
     */
    private static String key(String strategyCode) {
        return strategyCode == null ? null : strategyCode.toUpperCase();
    }

    /** Open trade count across all strategies. Compared against the
     *  run's {@code maxConcurrentStrategies} cap at entry-gate time. */
    public int countActiveTrades() {
        return activeTradesByStrategy == null ? 0 : activeTradesByStrategy.size();
    }

    /** Whether the named strategy already holds an open trade. */
    public boolean hasActiveTradeFor(String strategyCode) {
        if (strategyCode == null || activeTradesByStrategy == null) return false;
        return activeTradesByStrategy.containsKey(key(strategyCode));
    }

    /**
     * V62 — count active trades on a given side ("LONG" / "SHORT"). Used by
     * {@code RiskGuardService.evaluate}'s account-level concurrent-cap gate
     * when running the backtest path: backtest is single-account so we can
     * ignore the {@code accountId} parameter the gate passes and just count
     * matching trades in the current state.
     */
    public long countOpenTradesBySide(String side) {
        if (side == null || activeTradesByStrategy == null) return 0L;
        long count = 0L;
        for (BacktestTrade t : activeTradesByStrategy.values()) {
            if (t != null && side.equalsIgnoreCase(t.getSide())) count++;
        }
        return count;
    }

    /**
     * Register a new active trade for a strategy. Updates BOTH the
     * multi-trade map AND the legacy single-trade slot so call sites
     * reading either see consistent state. Caller is responsible for
     * checking {@code countActiveTrades() < maxConcurrentStrategies}
     * before invoking — the cap gate lives in the orchestrator.
     */
    public void addActiveTrade(String strategyCode, BacktestTrade trade,
                               List<BacktestTradePosition> positions) {
        if (strategyCode == null || trade == null) return;
        String k = key(strategyCode);
        if (activeTradesByStrategy == null) activeTradesByStrategy = new java.util.LinkedHashMap<>();
        if (activeTradePositionsByStrategy == null) activeTradePositionsByStrategy = new java.util.LinkedHashMap<>();
        activeTradesByStrategy.put(k, trade);
        activeTradePositionsByStrategy.put(k,
                positions != null ? positions : new ArrayList<>());
        // Legacy mirror: most-recently-opened trade is what reads see.
        this.activeTrade = trade;
        this.activeTradePositions = positions != null ? positions : new ArrayList<>();
    }

    /**
     * Clear the strategy's active trade slot when its trade closes.
     * Refreshes the legacy mirror to point at any remaining active trade,
     * or null when none remain.
     */
    public void removeActiveTrade(String strategyCode) {
        if (strategyCode == null || activeTradesByStrategy == null) return;
        String k = key(strategyCode);
        activeTradesByStrategy.remove(k);
        if (activeTradePositionsByStrategy != null) {
            activeTradePositionsByStrategy.remove(k);
        }
        // Refresh legacy mirror — first remaining trade or null. With
        // LinkedHashMap "first" means earliest insertion that still exists,
        // so the mirror is deterministic across runs.
        if (activeTradesByStrategy.isEmpty()) {
            this.activeTrade = null;
            this.activeTradePositions = new ArrayList<>();
        } else {
            java.util.Map.Entry<String, BacktestTrade> first =
                    activeTradesByStrategy.entrySet().iterator().next();
            this.activeTrade = first.getValue();
            this.activeTradePositions = activeTradePositionsByStrategy != null
                    ? activeTradePositionsByStrategy.getOrDefault(first.getKey(), new ArrayList<>())
                    : new ArrayList<>();
        }
    }

    // ── Per-strategy pending entries ────────────────────────────────────

    /**
     * Pending-entry count across all strategies. The orchestrator's cap
     * check needs this to enforce {@code maxConcurrentStrategies} correctly:
     * pending entries fill on the next bar so they're effectively part of
     * the future-active-trade count. Without this, two strategies signaling
     * entries on the same monitor candle could both store pending and both
     * fill on the next bar, exceeding the cap.
     */
    public int countPendingEntries() {
        return pendingEntriesByStrategy == null ? 0 : pendingEntriesByStrategy.size();
    }

    public boolean hasPendingEntryFor(String strategyCode) {
        if (strategyCode == null || pendingEntriesByStrategy == null) return false;
        return pendingEntriesByStrategy.containsKey(key(strategyCode));
    }

    public PendingEntry getPendingEntryFor(String strategyCode) {
        if (strategyCode == null || pendingEntriesByStrategy == null) return null;
        return pendingEntriesByStrategy.get(key(strategyCode));
    }

    public void setPendingEntryFor(String strategyCode, PendingEntry pending) {
        if (strategyCode == null || pending == null) return;
        if (pendingEntriesByStrategy == null) pendingEntriesByStrategy = new java.util.LinkedHashMap<>();
        pendingEntriesByStrategy.put(key(strategyCode), pending);
        // Legacy mirror: most-recently-queued pending entry.
        this.pendingEntry = pending;
    }

    public void clearPendingEntryFor(String strategyCode) {
        if (strategyCode == null || pendingEntriesByStrategy == null) return;
        pendingEntriesByStrategy.remove(key(strategyCode));
        // Refresh legacy mirror — pick any remaining pending or null.
        if (pendingEntriesByStrategy.isEmpty()) {
            this.pendingEntry = null;
        } else {
            this.pendingEntry = pendingEntriesByStrategy.entrySet().iterator().next().getValue();
        }
    }

    /**
     * Resolve the active trade for a strategy code, honouring case
     * normalisation. Used by callers that need the trade object directly
     * — multi-trade aware, doesn't fall through to the legacy mirror.
     */
    public BacktestTrade getActiveTradeFor(String strategyCode) {
        if (strategyCode == null || activeTradesByStrategy == null) return null;
        return activeTradesByStrategy.get(key(strategyCode));
    }

    /**
     * Same as {@link #getActiveTradeFor} but for the position list.
     * Returns an empty list when the strategy has no active trade
     * (never null, so callers can iterate without a null check).
     */
    public List<BacktestTradePosition> getActivePositionsFor(String strategyCode) {
        if (strategyCode == null || activeTradePositionsByStrategy == null) return List.of();
        List<BacktestTradePosition> p = activeTradePositionsByStrategy.get(key(strategyCode));
        return p == null ? List.of() : p;
    }
}