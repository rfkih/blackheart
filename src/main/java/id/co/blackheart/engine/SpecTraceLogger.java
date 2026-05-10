package id.co.blackheart.engine;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.SpecTrace;
import id.co.blackheart.repository.SpecTraceRepository;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Writes one {@link SpecTrace} row per spec-driven engine evaluation.
 *
 * <p>Backtest path (source = "backtest") records every call — replay needs
 * dense coverage. Live path samples at {@code engine.trace.sample-rate}
 * (default {@code 0.01}); the live SPI is hot enough that a 1% sample is
 * enough to surface error spikes via {@code EngineMetrics} without paying
 * the write cost on every bar.
 *
 * <p>All writes happen in a {@code REQUIRES_NEW} transaction so a trace
 * failure (DB hiccup, JSON serialization error) cannot roll back the
 * caller's tx — an in-flight live order must never be undone by an audit
 * miss. Exceptions are logged at {@code WARN} and swallowed.
 */
@Component
@Slf4j
public class SpecTraceLogger {

    private static final String EXEC_SOURCE_KEY = "source";
    private static final String EXEC_SOURCE_BACKTEST = "backtest";
    private static final String EXEC_BACKTEST_RUN_ID_KEY = "backtestRunId";

    private final SpecTraceRepository repository;
    private final TransactionTemplate isolatedTx;
    private final double liveSampleRate;

    public SpecTraceLogger(SpecTraceRepository repository,
                           PlatformTransactionManager txManager,
                           @Value("${engine.trace.sample-rate:0.01}") double liveSampleRate) {
        this.repository = repository;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        // REQUIRES_NEW so a trace failure cannot roll back the live caller's
        // transaction. An in-flight order must never be undone by an audit miss.
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.isolatedTx = tx;
        // Clamp to [0, 1]; an out-of-range config shouldn't disable tracing
        // silently nor write 200% of the time.
        this.liveSampleRate = Math.clamp(liveSampleRate, 0.0, 1.0);
        log.info("SpecTraceLogger initialised | liveSampleRate={}", this.liveSampleRate);
    }

    /**
     * Record a single engine evaluation. Safe to call from inside the engine
     * adapter's normal flow — exceptions are caught and never rethrown.
     *
     * @param spec      the spec actually evaluated (post-merge with overrides)
     * @param context   the enriched context the engine saw
     * @param decision  the decision returned by the engine; may be null when
     *                  {@code error} is non-null
     * @param latencyNs nanoseconds elapsed inside {@link StrategyEngine#evaluate}
     * @param error     the throwable caught by the adapter, or {@code null} on
     *                  the happy path
     * @param rules     optional per-rule trace entries; pass {@code null} for an
     *                  empty array (engines wire this in M3.2+ as they emit)
     */
    public void recordTrace(StrategySpec spec,
                            EnrichedStrategyContext context,
                            StrategyDecision decision,
                            long latencyNs,
                            Throwable error,
                            List<Map<String, Object>> rules) {
        try {
            if (!shouldRecord(context)) return;
            SpecTrace trace = buildTrace(spec, context, decision, latencyNs, error, rules);
            isolatedTx.executeWithoutResult(status -> repository.save(trace));
        } catch (RuntimeException ex) {
            // Trace writes are best-effort by design — they must never break
            // a live trading flow. Log loud enough that EngineMetrics will
            // notice if it starts happening at scale.
            log.warn("Failed to record spec_trace for code={} — swallowed",
                    spec == null ? null : spec.getStrategyCode(), ex);
        }
    }

    private SpecTrace buildTrace(StrategySpec spec,
                                 EnrichedStrategyContext context,
                                 StrategyDecision decision,
                                 long latencyNs,
                                 Throwable error,
                                 List<Map<String, Object>> rules) {
        return SpecTrace.builder()
                .traceId(UUID.randomUUID())
                .backtestRunId(extractBacktestRunId(context))
                .accountStrategyId(extractAccountStrategyId(context))
                .strategyCode(spec == null ? null : spec.getStrategyCode())
                .barTime(extractBarTime(context))
                .phase(derivePhase(decision, context))
                .specSnapshot(snapshotSpec(spec))
                .rules(rules == null ? new ArrayList<>() : rules)
                .decision(deriveDecisionLabel(decision, error))
                .decisionReason(deriveReason(decision, error))
                .evalLatencyUs(toMicros(latencyNs))
                .errorClass(error == null ? null : error.getClass().getName())
                .errorMessage(error == null ? null : truncate(error.getMessage(), 4000))
                .createdTime(LocalDateTime.now())
                .build();
    }

    // ── Sampling ─────────────────────────────────────────────────────────────

    private boolean shouldRecord(EnrichedStrategyContext context) {
        if (isBacktest(context)) return true;
        if (liveSampleRate <= 0.0) return false;
        if (liveSampleRate >= 1.0) return true;
        return ThreadLocalRandom.current().nextDouble() < liveSampleRate;
    }

    private boolean isBacktest(EnrichedStrategyContext context) {
        if (context == null || context.getExecutionMetadata() == null) return false;
        Object source = context.getExecutionMetadata().get(EXEC_SOURCE_KEY);
        return EXEC_SOURCE_BACKTEST.equalsIgnoreCase(String.valueOf(source));
    }

    // ── Field derivation ─────────────────────────────────────────────────────

    private UUID extractBacktestRunId(EnrichedStrategyContext context) {
        if (context == null || context.getExecutionMetadata() == null) return null;
        Object raw = context.getExecutionMetadata().get(EXEC_BACKTEST_RUN_ID_KEY);
        if (raw instanceof UUID u) return u;
        if (raw instanceof String s) {
            try { return UUID.fromString(s); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        return null;
    }

    private UUID extractAccountStrategyId(EnrichedStrategyContext context) {
        if (context == null) return null;
        AccountStrategy as = context.getAccountStrategy();
        return as == null ? null : as.getAccountStrategyId();
    }

    private LocalDateTime extractBarTime(EnrichedStrategyContext context) {
        if (context == null) return LocalDateTime.now();
        MarketData md = context.getMarketData();
        if (md != null && md.getEndTime() != null) return md.getEndTime();
        return LocalDateTime.now();
    }

    private String derivePhase(StrategyDecision decision, EnrichedStrategyContext context) {
        if (decision == null || decision.getDecisionType() == null) return "ERROR";
        DecisionType type = decision.getDecisionType();
        return switch (type) {
            case OPEN_LONG -> "ENTRY_LONG";
            case OPEN_SHORT -> "ENTRY_SHORT";
            case CLOSE_LONG, CLOSE_SHORT -> "EXIT";
            case UPDATE_POSITION_MANAGEMENT -> "MANAGE";
            case HOLD -> (context != null && context.hasTradablePosition()) ? "MANAGE" : "SCAN";
        };
    }

    private String deriveDecisionLabel(StrategyDecision decision, Throwable error) {
        if (error != null) return "ERROR";
        if (decision == null || decision.getDecisionType() == null) return "NO_TRADE";
        return decision.getDecisionType().name();
    }

    private String deriveReason(StrategyDecision decision, Throwable error) {
        if (error != null) return error.getMessage();
        if (decision == null) return null;
        if (decision.getReason() != null) return decision.getReason();
        if (decision.getExitReason() != null) return decision.getExitReason();
        return decision.getVetoReason();
    }

    private Integer toMicros(long latencyNs) {
        if (latencyNs <= 0L) return 0;
        long us = latencyNs / 1_000L;
        return us > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) us;
    }

    private Map<String, Object> snapshotSpec(StrategySpec spec) {
        Map<String, Object> snap = HashMap.newHashMap(7);
        if (spec == null) return snap;
        snap.put("strategyCode", spec.getStrategyCode());
        snap.put("strategyName", spec.getStrategyName());
        snap.put("archetype", spec.getArchetype());
        snap.put("archetypeVersion", spec.getArchetypeVersion());
        snap.put("specSchemaVersion", spec.getSpecSchemaVersion());
        // Defensive copies: the underlying maps are nominally immutable after
        // construction, but a downstream mutation (engine bug, override merge)
        // would otherwise leak into the persisted snapshot.
        snap.put("params", spec.getParams() == null ? new HashMap<>() : new HashMap<>(spec.getParams()));
        snap.put("body", spec.getBody() == null ? new HashMap<>() : new HashMap<>(spec.getBody()));
        return snap;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
