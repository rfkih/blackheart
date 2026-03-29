package id.co.blackheart.service.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StrategyExecutorFactory {

    private final TrendPullbackSingleExitStrategyService trendPullbackSingleExitStrategyService;
    private final ExecutionTestService executionTestService;
    private final RahtV1StrategyExecutor rahtV1StrategyExecutor;

    public StrategyExecutor get(String strategyName) {
        return switch (strategyName) {
            case "TREND_PULLBACK_SINGLE_EXIT" -> trendPullbackSingleExitStrategyService;
            case "TEST" -> executionTestService;
            case "RAHT_V1" -> rahtV1StrategyExecutor;
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategyName);
        };
    }
}
