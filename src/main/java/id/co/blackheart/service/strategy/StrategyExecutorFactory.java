package id.co.blackheart.service.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyExecutorFactory {
    private final ExecutionTestService executionTestService;
    private final VcbStrategyService vcbStrategyService;
    private final LsrStrategyService lsrStrategyService;
    private final TrendPullbackStrategyService trendPullbackStrategyService;
    private final VolatilityBreakoutStrategyService volatilityBreakoutStrategyService;

    public StrategyExecutor get(String strategyName) {
        log.info("Getting strategy executor for strategy: {}", strategyName);
        return switch (strategyName) {
            case "TEST" -> executionTestService;
            case "VCB" -> vcbStrategyService;
            case "LSR" -> lsrStrategyService;
            case "TPR" -> trendPullbackStrategyService;
            case "VBO" -> volatilityBreakoutStrategyService;
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategyName);
        };
    }
}
