package id.co.blackheart.repository;

import id.co.blackheart.model.FeatureStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FeatureStoreRepository extends JpaRepository<FeatureStore, Long> {
//    List<FeatureStore> findBySymbolAndTimestampBetween(String symbol, LocalDateTime start, LocalDateTime end);
//    FeatureStore findTopBySymbolOrderByTimestampDesc(String symbol);

    @Query(
            value = """
                SELECT EXISTS (
                    SELECT 1
                    FROM feature_store
                    WHERE symbol = :symbol
                      AND "interval" = :interval
                      AND start_time = :startTime
                )
                """,
            nativeQuery = true
    )
    boolean existsBySymbolAndIntervalAndStartTime(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("startTime") LocalDateTime startTime
    );
}
