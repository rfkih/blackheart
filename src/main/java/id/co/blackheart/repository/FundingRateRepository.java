package id.co.blackheart.repository;

import id.co.blackheart.model.FundingRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read/write surface for {@link FundingRate}. Backfill (Phase 4.2) and the
 * 8-hourly scheduler (Phase 4.8) write here; the FeatureStore backfill
 * (Phase 4.5) and per-bar funding-cost lookup (Phase 4.6) read from here.
 */
@Repository
public interface FundingRateRepository extends JpaRepository<FundingRate, FundingRate.FundingRateId> {

    /**
     * Most recent funding event for a symbol whose {@code fundingTime} is at
     * or before the supplied boundary. The boundary is the candle's
     * {@code endTime} on the per-bar lookup path — i.e. the funding rate
     * "in effect" on the bar that just closed.
     */
    @Query("""
        SELECT fr FROM FundingRate fr
         WHERE fr.symbol = :symbol
           AND fr.fundingTime <= :boundary
         ORDER BY fr.fundingTime DESC
        """)
    List<FundingRate> findLatestBefore(@Param("symbol") String symbol,
                                       @Param("boundary") LocalDateTime boundary);

    default Optional<FundingRate> findLatest(String symbol, LocalDateTime boundary) {
        List<FundingRate> rows = findLatestBefore(symbol, boundary);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Latest known funding event per symbol — used by the 8-hourly scheduler
     * to compute the {@code startTime} for the next incremental fetch.
     */
    Optional<FundingRate> findFirstBySymbolOrderByFundingTimeDesc(String symbol);

    long countBySymbol(String symbol);

    /**
     * Funding events for a symbol in {@code (lowerExclusive, upperInclusive]}.
     * Used by the FeatureStore funding-column computation: pass
     * {@code lowerExclusive = boundary - 7 days} and {@code upperInclusive =
     * boundary} to load the rolling 7-day window. Ordered ascending so the
     * caller can iterate without re-sorting.
     */
    @Query("""
        SELECT fr FROM FundingRate fr
         WHERE fr.symbol = :symbol
           AND fr.fundingTime >  :lowerExclusive
           AND fr.fundingTime <= :upperInclusive
         ORDER BY fr.fundingTime ASC
        """)
    List<FundingRate> findInWindow(@Param("symbol") String symbol,
                                   @Param("lowerExclusive") LocalDateTime lowerExclusive,
                                   @Param("upperInclusive") LocalDateTime upperInclusive);

    /**
     * All funding events for a symbol with {@code funding_time <= boundary},
     * ordered ascending. Used by the bulk funding-column backfill which loads
     * the full series once and walks it in lockstep with feature_store rows.
     */
    @Query("""
        SELECT fr FROM FundingRate fr
         WHERE fr.symbol = :symbol
           AND fr.fundingTime <= :boundary
         ORDER BY fr.fundingTime ASC
        """)
    List<FundingRate> findAllUpTo(@Param("symbol") String symbol,
                                  @Param("boundary") LocalDateTime boundary);
}
