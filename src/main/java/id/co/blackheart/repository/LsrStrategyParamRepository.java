package id.co.blackheart.repository;

import id.co.blackheart.model.LsrStrategyParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LsrStrategyParamRepository extends JpaRepository<LsrStrategyParam, UUID> {

    Optional<LsrStrategyParam> findByAccountStrategyId(UUID accountStrategyId);

    boolean existsByAccountStrategyId(UUID accountStrategyId);

    @Modifying
    @Query("DELETE FROM LsrStrategyParam p WHERE p.accountStrategyId = :accountStrategyId")
    int deleteByAccountStrategyId(@Param("accountStrategyId") UUID accountStrategyId);
}
