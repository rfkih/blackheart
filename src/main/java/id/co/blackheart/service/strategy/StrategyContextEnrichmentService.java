package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.BaseStrategyContext;
import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyRequirements;

public interface StrategyContextEnrichmentService {

    EnrichedStrategyContext enrich(
            BaseStrategyContext baseContext,
            StrategyRequirements requirements
    );

    default EnrichedStrategyContext enrich(BaseStrategyContext baseContext) {
        return enrich(baseContext, StrategyRequirements.builder().build());
    }

    default boolean supports(StrategyRequirements requirements) {
        return true;
    }
}