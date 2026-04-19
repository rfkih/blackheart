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
    WHERE account_id = :accountId
      AND asset = :asset
    LIMIT 1
    """, nativeQuery = true)
    Optional<Portfolio> findByAccountIdAndAsset(
            @Param("accountId") UUID accountId,
            @Param("asset") String asset
    );

    @Query(value = """
    SELECT *
    FROM portfolio p
    WHERE p.account_id = :accountId
      AND p.asset IN (:assets)
      AND p.is_active = '1'
    """, nativeQuery = true)
    List<Portfolio> findAllByAccountIdAndAssetIn(UUID accountId, List<String> assets);

    @Query(value = """
    SELECT *
    FROM portfolio p
    WHERE p.account_id = :accountId
      AND p.is_active = '1'
    ORDER BY p.asset ASC
    """, nativeQuery = true)
    List<Portfolio> findAllByAccountId(@Param("accountId") UUID accountId);
}
