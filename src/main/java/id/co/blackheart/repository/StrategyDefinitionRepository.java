package id.co.blackheart.repository;

import id.co.blackheart.model.StrategyDefinition;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StrategyDefinitionRepository extends JpaRepository<StrategyDefinition, UUID> {

    Optional<StrategyDefinition> findByStrategyCode(String strategyCode);

    /**
     * Pessimistic write lock for definition-scope promote (V40). Serializes concurrent
     * {@code POST /strategy-promotion/definition/{code}/promote} callers so the read of
     * current state and the subsequent flip+log row write happen atomically per strategy.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sd FROM StrategyDefinition sd WHERE sd.strategyCode = :strategyCode")
    Optional<StrategyDefinition> findByStrategyCodeForUpdate(@Param("strategyCode") String strategyCode);

    /**
     * Filterable + paginated catalogue. Substring search runs over both
     * {@code strategy_code} and {@code strategy_name} (case-insensitive ILIKE).
     * Sort is applied by the {@link Pageable} — Spring resolves
     * {@code ?sort=field,asc|desc} to the right ORDER BY clause.
     *
     * <p>The {@code CAST(:query AS string)} wrapper exists for the same reason
     * as {@code findRecentFiltered} on StrategyPromotionLogRepository: a
     * nullable String parameter shows up as {@code bytea} to PostgreSQL
     * otherwise.
     */
    @Query("""
            SELECT sd FROM StrategyDefinition sd
            WHERE :query IS NULL
               OR LOWER(sd.strategyCode) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
               OR LOWER(sd.strategyName) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
            """)
    Page<StrategyDefinition> findFiltered(
            @Param("query") String query,
            Pageable pageable);
}
