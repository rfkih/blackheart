package id.co.blackheart.repository;

import id.co.blackheart.model.StrategyPromotionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategyPromotionLogRepository extends JpaRepository<StrategyPromotionLog, UUID> {

    /** Most-recent promotion row for the given strategy — defines the current state. */
    Optional<StrategyPromotionLog> findFirstByAccountStrategyIdOrderByCreatedTimeDesc(UUID accountStrategyId);

    List<StrategyPromotionLog> findByAccountStrategyIdOrderByCreatedTimeDesc(UUID accountStrategyId);

    /** V40 — definition-scope counterpart of the account-scope finder. */
    Optional<StrategyPromotionLog> findFirstByStrategyDefinitionIdOrderByCreatedTimeDesc(UUID strategyDefinitionId);

    List<StrategyPromotionLog> findByStrategyDefinitionIdOrderByCreatedTimeDesc(UUID strategyDefinitionId);

    /** Cross-strategy feed for the /research dashboard — newest promotions across all strategies. */
    @Query("SELECT l FROM StrategyPromotionLog l ORDER BY l.createdTime DESC")
    List<StrategyPromotionLog> findRecent(Pageable pageable);

    /**
     * Filterable feed for the /research recent-promotions panel. Both filters are
     * optional; null/blank means "no filter on this column". Uppercase comparison on
     * strategy_code mirrors how the column is stored. Results paged + ordered by
     * createdTime DESC.
     *
     * The CAST(:p AS string) wrappers exist because Hibernate 6 + PostgreSQL infer
     * a nullable String parameter as `bytea` when it appears only inside an
     * `IS NULL OR …` branch — which makes `UPPER(?)` resolve to `upper(bytea)`
     * (no such function). Casting pins the parameter type to varchar regardless
     * of null state.
     */
    @Query("""
            SELECT l FROM StrategyPromotionLog l
            WHERE (:strategyCode IS NULL OR UPPER(l.strategyCode) = UPPER(CAST(:strategyCode AS string)))
              AND (:toState IS NULL OR l.toState = CAST(:toState AS string))
            ORDER BY l.createdTime DESC
            """)
    Page<StrategyPromotionLog> findRecentFiltered(
            @Param("strategyCode") String strategyCode,
            @Param("toState") String toState,
            Pageable pageable);
}
