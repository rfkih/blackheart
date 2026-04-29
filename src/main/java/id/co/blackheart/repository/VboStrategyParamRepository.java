package id.co.blackheart.repository;

import id.co.blackheart.model.VboStrategyParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VboStrategyParamRepository extends JpaRepository<VboStrategyParam, UUID> {

    Optional<VboStrategyParam> findByAccountStrategyId(UUID accountStrategyId);

    @Modifying
    @Query("DELETE FROM VboStrategyParam p WHERE p.accountStrategyId = :accountStrategyId")
    int deleteByAccountStrategyId(@Param("accountStrategyId") UUID accountStrategyId);
}
