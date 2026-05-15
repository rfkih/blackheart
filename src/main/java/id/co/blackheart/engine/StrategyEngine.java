package id.co.blackheart.engine;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;

/**
 * Service-provider interface for spec-driven strategy archetypes.
 *
 * <p>One implementation per archetype (e.g. {@code mean_reversion_oscillator},
 * {@code trend_pullback}). The {@link StrategyEngineRegistry} maps an
 * archetype name to its engine; the {@code StrategyExecutorFactory} wraps
 * the chosen engine + a {@link StrategySpec} into a {@link
 * id.co.blackheart.service.strategy.StrategyExecutor} so the rest of the
 * pipeline (live + backtest) remains unchanged.
 *
 * <p>Engines are stateless beans. The spec carries all per-strategy state.
 * The context carries all per-bar state.
 */
public interface StrategyEngine {

    /** Archetype this engine handles, e.g. {@code "mean_reversion_oscillator"}. */
    String archetype();

    /**
     * Highest archetype-version this engine knows how to evaluate.
     * Specs whose {@code archetypeVersion} exceeds this fail fast at
     * factory time — older specs may be forward-migrated by a
     * {@code SpecVersionAdapter} (M4).
     */
    int supportedVersion();

    /**
     * Static requirements that the context-enrichment layer needs to honor
     * (bias TF, previousFeatureStore, etc.). Most archetypes can return
     * {@link StrategyRequirements#defaults()} — engines that need bias data
     * or previous-bar features must say so.
     */
    StrategyRequirements requirements(StrategySpec spec);

    /**
     * Evaluate the spec against the current bar's context. Same contract as
     * {@link id.co.blackheart.service.strategy.StrategyExecutor#execute}: returns
     * a non-null decision; {@code HOLD} for "no action this bar".
     */
    StrategyDecision evaluate(StrategySpec spec, EnrichedStrategyContext context);
}
