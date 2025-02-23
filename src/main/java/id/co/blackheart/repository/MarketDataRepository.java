package id.co.blackheart.repository;

import id.co.blackheart.model.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MarketDataRepository extends JpaRepository<MarketData, Long> {
    List<MarketData> findBySymbolAndStartTimeBetween(String symbol, Instant start, Instant end);

    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol AND m.interval = :interval ORDER BY m.startTime DESC LIMIT 50")
    List<MarketData> findLast50BySymbolAndInterval(String symbol, String interval);

}
