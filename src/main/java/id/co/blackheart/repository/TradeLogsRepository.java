package id.co.blackheart.repository;

import id.co.blackheart.model.TradeLogs;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TradeLogsRepository extends JpaRepository<TradeLogs, Long> {
    List<TradeLogs> findByUserId(Long userId);
    List<TradeLogs> findByUserIdAndSymbol(Long userId, String symbol);
    List<TradeLogs> findByTradeOutcome(String tradeOutcome);
}

