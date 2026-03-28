package id.co.blackheart.repository;

import id.co.blackheart.model.AccountStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AccountStrategyRepository extends JpaRepository<AccountStrategy, UUID> {

    @Query(value = """
        SELECT acs.*
        FROM account_strategy acs
        WHERE acs.enabled = true
          AND acs.interval_name = :interval
        ORDER BY acs.priority_order ASC, acs.created_at ASC
        """, nativeQuery = true)
    List<AccountStrategy> findByEnabledTrueAndIntervalName(@Param("interval") String interval);
}