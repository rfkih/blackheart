package id.co.blackheart.service.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyExecutorFactory {

    private final TrendPullbackSingleExitStrategyService trendPullbackSingleExitStrategyService;
    private final ExecutionTestService executionTestService;
    private final RahtV1StrategyExecutor rahtV1StrategyExecutor;
    private final TsMomV1StrategyService tsMomV1StrategyService;
    private final ScalpMomV1StrategyService scalpMomV1StrategyService;

    public StrategyExecutor get(String strategyName) {
        log.info("Getting strategy executor for strategy: {}", strategyName);
        return switch (strategyName) {
            case "TREND_PULLBACK_SINGLE_EXIT" -> trendPullbackSingleExitStrategyService;
            case "TEST" -> executionTestService;
            case "RAHT_V1" -> rahtV1StrategyExecutor;
            case "TSMOM_V1" -> tsMomV1StrategyService;
            case "SCALP_MOM_V1" -> scalpMomV1StrategyService;
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategyName);
        };
    }
}
