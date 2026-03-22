package id.co.blackheart.repository;

import id.co.blackheart.model.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {
    @Query(value = """
    SELECT *
    FROM portfolio
    WHERE user_id = :userId
      AND asset = :asset
    LIMIT 1
    """, nativeQuery = true)
    Optional<Portfolio> findByUserIdAndAsset(
            @Param("userId") UUID userId,
            @Param("asset") String asset
    );

    @Query(value = """
    SELECT *
    FROM portfolio p
    WHERE p.user_id = :userId
      AND p.asset IN (:assets)
      AND p.is_active = 'Y'
    """, nativeQuery = true)
    List<Portfolio> findAllByUserIdAndAssetIn(UUID userId, List<String> assets);
}
