package id.co.blackheart.service.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StrategyExecutorFactory {

    private final TimeSeriesMomentumService tsmom4h;
    private final Breakout4hStrategyService breakout4h;
    private final Pullback15mWith4hBiasStrategyService pullback15mBias;
    private final TrendFollowingStrategyService trendFollowing;

    public StrategyExecutor get(String strategyName) {
        return switch (strategyName) {
            case "TSMOM" -> tsmom4h;
            case "BREAKOUT" -> breakout4h;
            case "PULLBACK_15M_WITH_4H_BIAS" -> pullback15mBias;
            case "TREND_FOLLOWING" -> trendFollowing;
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategyName);
        };
    }
}
