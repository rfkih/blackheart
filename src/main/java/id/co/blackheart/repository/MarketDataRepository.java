package id.co.blackheart.repository;

import id.co.blackheart.model.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarketDataRepository extends JpaRepository<MarketData, Long> {

    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol AND m.interval = :interval ORDER BY m.startTime DESC LIMIT 100")
    List<MarketData> findLast100BySymbolAndInterval(String symbol, String interval);

    @Query("SELECT m FROM MarketData m WHERE m.symbol = :symbol AND m.interval = :interval  ORDER BY m.endTime DESC LIMIT 1")
    MarketData findLatestBySymbol(String symbol, String interval);
}
