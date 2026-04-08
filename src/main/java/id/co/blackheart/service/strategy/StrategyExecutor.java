package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;

public interface StrategyExecutor {

    StrategyRequirements getRequirements();

    StrategyDecision execute(EnrichedStrategyContext context);
}