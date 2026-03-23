package id.co.blackheart.service.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StrategyExecutorFactory {

    private final TimeSeriesMomentumService tsmom;
//    private final Breakout4hStrategyService breakout;
    private final TrendPullbackSingleExitStrategyService trendPullbackSingleExitStrategyService;

    private final TrendFollowingStrategyService trendFollowing;
    private final ExecutionTestService executionTestService;

    public StrategyExecutor get(String strategyName) {
        return switch (strategyName) {
            case "TSMOM" -> tsmom;
//            case "BREAKOUT" -> breakout;
            case "TREND_PULLBACK_SINGLE_EXIT" -> trendPullbackSingleExitStrategyService;
            case "TREND_FOLLOWING" -> trendFollowing;
            case "TEST" -> executionTestService;
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategyName);
        };
    }
}
