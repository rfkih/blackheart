package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BacktestRunRepository extends JpaRepository<BacktestRun, UUID> {

    @Query(value = """
            SELECT * FROM backtest_run
            ORDER BY created_time DESC
            LIMIT :limitVal OFFSET :offsetVal
            """, nativeQuery = true)
    List<BacktestRun> findAllOrderByCreatedTimeDesc(
            @Param("limitVal") int limitVal,
            @Param("offsetVal") int offsetVal
    );

    @Query(value = "SELECT COUNT(*) FROM backtest_run", nativeQuery = true)
    long countAll();
}