package id.co.blackheart.engine;

/**
 * Forward migration for a single (archetype, fromVersion) pair. One bean per
 * step in the migration chain — e.g. a v1→v2 trend_pullback migration is one
 * {@code SpecMigration}; a separate v2→v3 migration would be another. The
 * {@link SpecVersionAdapter} composes them automatically.
 *
 * <p>Migrations must be pure: read the source spec, return a new
 * {@link StrategySpec} whose {@link StrategySpec#getArchetypeVersion()} is
 * {@code fromVersion() + 1}. They should not mutate the input spec or
 * persist anything — the adapter applies them at executor-build time, and
 * the result stays in memory until the cache is invalidated.
 */
public interface SpecMigration {

    /** Archetype this migration applies to (case-insensitive match). */
    String archetype();

    /** Source spec version. The adapter applies this when current version equals this value. */
    int fromVersion();

    /**
     * Produce a spec whose {@code archetypeVersion} is {@code fromVersion() + 1},
     * preserving everything else unless the schema change requires a transform.
     */
    StrategySpec migrate(StrategySpec source);
}
