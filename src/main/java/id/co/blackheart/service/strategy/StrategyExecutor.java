package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;

public interface StrategyExecutor {
    StrategyDecision execute(StrategyContext context);
}
