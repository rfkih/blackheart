package id.co.blackheart.service.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StrategyExecutorFactory {

    private final TimeSeriesMomentumService tsmom;
//    private final Breakout4hStrategyService breakout;
//    private final Pullback15mWith4hBiasStrategyService pullback15mBias;
    private final TrendFollowingStrategyService trendFollowing;
    private final ExecutionTestService executionTestService;

    public StrategyExecutor get(String strategyName) {
        return switch (strategyName) {
            case "TSMOM" -> tsmom;
//            case "BREAKOUT" -> breakout;
//            case "PULLBACK_15M_WITH_4H_BIAS" -> pullback15mBias;
            case "TREND_FOLLOWING" -> trendFollowing;
            case "TEST" -> executionTestService;
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategyName);
        };
    }
}
