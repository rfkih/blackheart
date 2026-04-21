package id.co.blackheart.repository;

import id.co.blackheart.model.BacktestTradePosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BacktestTradePositionRepository extends JpaRepository<BacktestTradePosition, UUID> {



    @Query(value = """
        SELECT *
        FROM backtest_trade_position btp
        WHERE btp.backtest_trade_id = :tradeId
        ORDER BY btp.entry_time ASC
        """, nativeQuery = true)
    List<BacktestTradePosition> findAllByTradeId(UUID tradeId);
}