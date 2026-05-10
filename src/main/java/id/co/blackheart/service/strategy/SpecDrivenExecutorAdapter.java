package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.engine.EngineMetrics;
import id.co.blackheart.engine.SpecTraceLogger;
import id.co.blackheart.engine.StrategyEngine;
import id.co.blackheart.engine.StrategySpec;
import id.co.blackheart.model.AccountStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Adapter that lets a spec-driven {@link StrategyEngine} satisfy the legacy
 * {@link StrategyExecutor} contract.
 *
 * <p>One adapter instance per strategy code: it carries the {@link StrategySpec}
 * resolved from {@code strategy_definition} (archetype defaults + body) and
 * pulls per-account overrides from {@link StrategyParamService} on every
 * {@link #execute(EnrichedStrategyContext)} call. The override read is
 * Redis-cached, so the per-tick cost is a cache hit, not a DB round-trip.
 *
 * <p>The adapter is intentionally stateless beyond its three injected fields
 * — the spec is logically immutable and the engine is a stateless bean, so
 * concurrent calls share safely.
 */
@Slf4j
public class SpecDrivenExecutorAdapter implements StrategyExecutor {

    private static final String EXEC_SOURCE_KEY = "source";
    private static final String EXEC_SOURCE_BACKTEST = "backtest";

    private final StrategySpec baseSpec;
    private final StrategyEngine engine;
    private final StrategyParamService paramService;
    private final SpecTraceLogger traceLogger;
    private final EngineMetrics engineMetrics;

    public SpecDrivenExecutorAdapter(StrategySpec baseSpec,
                                     StrategyEngine engine,
                                     StrategyParamService paramService,
                                     SpecTraceLogger traceLogger,
                                     EngineMetrics engineMetrics) {
        this.baseSpec = baseSpec;
        this.engine = engine;
        this.paramService = paramService;
        this.traceLogger = traceLogger;
        this.engineMetrics = engineMetrics;
    }

    @Override
    public StrategyRequirements getRequirements() {
        return engine.requirements(baseSpec);
    }

    @Override
    public StrategyDecision execute(EnrichedStrategyContext context) {
        UUID accountStrategyId = resolveAccountStrategyId(context);
        Map<String, Object> dbOverrides = paramService.resolveOverridesForStrategy(
                baseSpec.getStrategyCode(), accountStrategyId);
        Map<String, Object> sweepOverrides = BacktestParamOverrideContext.forStrategy(baseSpec.getStrategyCode());

        // Layer order (most specific wins): archetype defaults → DB → backtest sweep.
        // Mirrors how legacy LSR/VCB resolve params at execute time.
        Map<String, Object> layered;
        if (CollectionUtils.isEmpty(sweepOverrides)) {
            layered = dbOverrides;
        } else if (CollectionUtils.isEmpty(dbOverrides)) {
            layered = sweepOverrides;
        } else {
            layered = new HashMap<>(dbOverrides);
            layered.putAll(sweepOverrides);
        }

        StrategySpec resolvedSpec = baseSpec.merge(layered);

        StrategyDecision decision = null;
        Throwable error = null;
        long start = System.nanoTime();
        try {
            decision = engine.evaluate(resolvedSpec, context);
            return decision;
        } catch (RuntimeException ex) {
            // Capture the throwable so the trace row records it, then rethrow
            // — caller error handling stays intact.
            error = ex;
            throw ex;
        } finally {
            long elapsed = System.nanoTime() - start;
            if (traceLogger != null) {
                // Rules wiring lands per-archetype in M3.2+; null = empty array.
                traceLogger.recordTrace(resolvedSpec, context, decision, elapsed, error, null);
            }
            // Skip engine metrics for backtest evaluations — backtest errors
            // must not trip the live kill-switch, and counter inflation from
            // sweep runs would mask live error rates.
            if (engineMetrics != null && !isBacktest(context)) {
                String code = resolvedSpec == null ? null : resolvedSpec.getStrategyCode();
                if (error == null) {
                    engineMetrics.recordSuccess(code);
                } else {
                    engineMetrics.recordError(accountStrategyId, code, error);
                }
            }
        }
    }

    private boolean isBacktest(EnrichedStrategyContext context) {
        if (context == null || context.getExecutionMetadata() == null) return false;
        Object source = context.getExecutionMetadata().get(EXEC_SOURCE_KEY);
        return EXEC_SOURCE_BACKTEST.equalsIgnoreCase(String.valueOf(source));
    }

    private UUID resolveAccountStrategyId(EnrichedStrategyContext context) {
        if (context == null) return null;
        AccountStrategy as = context.getAccountStrategy();
        return as == null ? null : as.getAccountStrategyId();
    }
}
