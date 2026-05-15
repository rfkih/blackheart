package id.co.blackheart.repository;

import id.co.blackheart.model.MlIngestSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MlIngestScheduleRepository extends JpaRepository<MlIngestSchedule, Long> {

    /**
     * One row per (source, symbol). symbol may be NULL for macro-only sources;
     * the SQL UNIQUE constraint treats NULLs as distinct, so this query has
     * two branches.
     */
    @Query("""
        SELECT s FROM MlIngestSchedule s
        WHERE s.source = :source
          AND ((:symbol IS NULL AND s.symbol IS NULL) OR s.symbol = :symbol)
        """)
    Optional<MlIngestSchedule> findBySourceAndSymbol(
            @Param("source") String source,
            @Param("symbol") String symbol
    );

    List<MlIngestSchedule> findAllByOrderBySourceAscSymbolAsc();

    @Query("""
        SELECT s FROM MlIngestSchedule s
        WHERE s.enabled = true
        ORDER BY s.source ASC, s.symbol ASC
        """)
    List<MlIngestSchedule> findAllEnabled();
}
