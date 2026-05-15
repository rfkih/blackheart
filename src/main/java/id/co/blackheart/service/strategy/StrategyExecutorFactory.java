package id.co.blackheart.service.strategy;

import id.co.blackheart.engine.EngineMetrics;
import id.co.blackheart.engine.SpecTraceLogger;
import id.co.blackheart.engine.SpecVersionAdapter;
import id.co.blackheart.engine.StrategyEngine;
import id.co.blackheart.engine.StrategyEngineRegistry;
import id.co.blackheart.engine.StrategySpec;
import id.co.blackheart.model.StrategyDefinition;
import id.co.blackheart.repository.StrategyDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class StrategyExecutorFactory {

    private static final String LEGACY_ARCHETYPE = "LEGACY_JAVA";

    // ── Legacy Java executors (one bean per strategy code). ──────────────────
    private final ExecutionTestService executionTestService;
    private final VcbStrategyService vcbStrategyService;
    private final LsrStrategyService lsrStrategyService;
    private final TrendPullbackStrategyService trendPullbackStrategyService;
    private final VolatilityBreakoutStrategyService volatilityBreakoutStrategyService;
    private final FundingCarryStrategyService fundingCarryStrategyService;

    // ── Parametric path. Disabled by default; opt-in per environment. ────────
    private final StrategyDefinitionRepository definitionRepository;
    private final StrategyEngineRegistry engineRegistry;
    private final SpecVersionAdapter specVersionAdapter;
    private final StrategyParamService strategyParamService;
    private final SpecTraceLogger specTraceLogger;
    private final EngineMetrics engineMetrics;
    private final boolean parametricEnabled;

    /** Per-strategy-code cache of spec-driven adapters. Live ticks must not
     *  re-read {@code strategy_definition} from the DB on every fan-out. */
    private final Map<String, StrategyExecutor> specDrivenCache = new ConcurrentHashMap<>();

    public StrategyExecutorFactory(ExecutionTestService executionTestService,
                                   VcbStrategyService vcbStrategyService,
                                   LsrStrategyService lsrStrategyService,
                                   TrendPullbackStrategyService trendPullbackStrategyService,
                                   VolatilityBreakoutStrategyService volatilityBreakoutStrategyService,
                                   FundingCarryStrategyService fundingCarryStrategyService,
                                   StrategyDefinitionRepository definitionRepository,
                                   StrategyEngineRegistry engineRegistry,
                                   SpecVersionAdapter specVersionAdapter,
                                   StrategyParamService strategyParamService,
                                   SpecTraceLogger specTraceLogger,
                                   EngineMetrics engineMetrics,
                                   @Value("${blackheart.engine.parametric.enabled:false}") boolean parametricEnabled) {
        this.executionTestService = executionTestService;
        this.vcbStrategyService = vcbStrategyService;
        this.lsrStrategyService = lsrStrategyService;
        this.trendPullbackStrategyService = trendPullbackStrategyService;
        this.volatilityBreakoutStrategyService = volatilityBreakoutStrategyService;
        this.fundingCarryStrategyService = fundingCarryStrategyService;
        this.definitionRepository = definitionRepository;
        this.engineRegistry = engineRegistry;
        this.specVersionAdapter = specVersionAdapter;
        this.strategyParamService = strategyParamService;
        this.specTraceLogger = specTraceLogger;
        this.engineMetrics = engineMetrics;
        this.parametricEnabled = parametricEnabled;
        log.info("StrategyExecutorFactory initialised | parametricEnabled={}", parametricEnabled);
    }

    public StrategyExecutor get(String strategyCode) {
        log.info("Getting strategy executor for strategy: {}", strategyCode);
        if (parametricEnabled) {
            StrategyExecutor specDriven = specDrivenCache.computeIfAbsent(strategyCode, this::tryBuildSpecDriven);
            if (specDriven != NULL_MARKER) return specDriven;
        }
        return legacyGet(strategyCode);
    }

    /**
     * Definition-scope kill-switch. Returns empty when a
     * {@code strategy_definition} row exists for {@code strategyCode} with
     * {@code enabled=false}; otherwise returns the resolved executor.
     *
     * <p>Only the live path consults this. Backtest continues to use
     * {@link #get(String)} so research can still run sweeps on disabled
     * strategies. Legacy codes without a definition row fall through and
     * resolve normally — the kill-switch only fires when the operator has
     * explicitly demoted the definition.
     */
    public Optional<StrategyExecutor> getIfDefinitionEnabled(String strategyCode) {
        Optional<StrategyDefinition> defOpt = definitionRepository.findByStrategyCode(strategyCode);
        if (defOpt.isPresent() && Boolean.FALSE.equals(defOpt.get().getEnabled())) {
            log.debug("strategy_definition {} disabled at definition scope; skipping", strategyCode);
            return Optional.empty();
        }
        return Optional.of(get(strategyCode));
    }

    /**
     * Drop the adapter cache. Call after a {@code strategy_definition} mutation
     * that changes archetype, version, or spec body — otherwise the running
     * adapter keeps the old shape until the next JVM restart.
     */
    public void invalidateSpecCache() {
        specDrivenCache.clear();
    }

    /**
     * Drop a single entry from the cache. Used by the LISTEN/NOTIFY hot-reload
     * path so a single spec mutation does not punish the entire fan-out — the
     * other strategies keep their warm adapters. A {@code null} or unknown
     * code is a no-op.
     */
    public void invalidateSpecCache(String strategyCode) {
        if (strategyCode == null) return;
        StrategyExecutor removed = specDrivenCache.remove(strategyCode);
        if (removed != null) {
            log.info("Invalidated spec-driven cache entry for strategyCode={}", strategyCode);
        }
    }

    // ── Spec-driven path ─────────────────────────────────────────────────────

    private StrategyExecutor tryBuildSpecDriven(String strategyCode) {
        Optional<StrategyDefinition> defOpt = definitionRepository.findByStrategyCode(strategyCode);
        if (defOpt.isEmpty()) return NULL_MARKER;
        StrategyDefinition def = defOpt.get();

        String archetype = def.getArchetype();
        if (archetype == null || LEGACY_ARCHETYPE.equalsIgnoreCase(archetype)) {
            return NULL_MARKER;
        }

        StrategyEngine engine = engineRegistry.require(archetype);
        int specVersion = def.getArchetypeVersion() == null ? 1 : def.getArchetypeVersion();
        if (specVersion > engine.supportedVersion()) {
            throw new IllegalStateException(
                    "Spec for strategyCode=" + strategyCode + " archetype=" + archetype
                            + " is at version " + specVersion
                            + " but engine supports only up to " + engine.supportedVersion()
                            + " — newer spec than engine; deploy a newer engine first.");
        }

        StrategySpec spec = buildBaseSpec(def);
        if (specVersion < engine.supportedVersion()) {
            spec = specVersionAdapter.upgrade(spec, engine.supportedVersion());
        }
        log.info("Built spec-driven executor | code={} archetype={} archetypeVersion={}→{}",
                strategyCode, archetype, specVersion, engine.supportedVersion());
        return new SpecDrivenExecutorAdapter(spec, engine, strategyParamService, specTraceLogger, engineMetrics);
    }

    @SuppressWarnings("unchecked")
    private StrategySpec buildBaseSpec(StrategyDefinition def) {
        Map<String, Object> body = def.getSpecJsonb() == null
                ? Collections.emptyMap()
                : new HashMap<>(def.getSpecJsonb());
        Object rawParams = body.get("params");
        Map<String, Object> defaults = (rawParams instanceof Map<?, ?> m)
                ? new HashMap<>((Map<String, Object>) m)
                : new HashMap<>();
        return StrategySpec.builder()
                .strategyCode(def.getStrategyCode())
                .strategyName(def.getStrategyName())
                .archetype(def.getArchetype())
                .archetypeVersion(def.getArchetypeVersion())
                .specSchemaVersion(def.getSpecSchemaVersion())
                .params(defaults)
                .body(body)
                .build();
    }

    // ── Legacy path ──────────────────────────────────────────────────────────

    private StrategyExecutor legacyGet(String strategyCode) {
        return switch (strategyCode) {
            case "TEST" -> executionTestService;
            case "VCB" -> vcbStrategyService;
            case "LSR" -> lsrStrategyService;
            case "TPR" -> trendPullbackStrategyService;
            case "VBO" -> volatilityBreakoutStrategyService;
            case "FCARRY" -> fundingCarryStrategyService;
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategyCode);
        };
    }

    /** Sentinel stored in the cache to mark "this code has no spec-driven path"
     *  so we don't re-query the DB on every miss. */
    private static final StrategyExecutor NULL_MARKER = new StrategyExecutor() {
        @Override public id.co.blackheart.dto.strategy.StrategyRequirements getRequirements() {
            throw new UnsupportedOperationException();
        }
        @Override public id.co.blackheart.dto.strategy.StrategyDecision execute(
                id.co.blackheart.dto.strategy.EnrichedStrategyContext context) {
            throw new UnsupportedOperationException();
        }
    };
}
