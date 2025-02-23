package id.co.blackheart.repository;

import id.co.blackheart.model.FeatureStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FeatureStoreRepository extends JpaRepository<FeatureStore, Long> {
    List<FeatureStore> findBySymbolAndTimestampBetween(String symbol, LocalDateTime start, LocalDateTime end);
    FeatureStore findTopBySymbolOrderByTimestampDesc(String symbol);
}
