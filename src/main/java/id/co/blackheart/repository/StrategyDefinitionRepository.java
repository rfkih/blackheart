package id.co.blackheart.repository;

import id.co.blackheart.model.StrategyDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StrategyDefinitionRepository extends JpaRepository<StrategyDefinition, UUID> {

    Optional<StrategyDefinition> findByStrategyCode(String strategyCode);
}
