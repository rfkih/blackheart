package id.co.blackheart.repository;

import id.co.blackheart.model.UserStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserStrategyRepository extends JpaRepository<UserStrategy, UUID> {

    @Query(value = """
        SELECT us.*
        FROM user_strategy us
        WHERE us.enabled = true
          AND us.interval_name = :interval
        ORDER BY us.priority_order ASC, us.created_at ASC
        """, nativeQuery = true)
    List<UserStrategy> findByEnabledTrueAndIntervalName(@Param("interval") String interval);
}