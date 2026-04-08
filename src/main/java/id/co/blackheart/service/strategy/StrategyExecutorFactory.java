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
    private final ScalpMomV1StrategyService scalpMomV1StrategyService;
    private final VcbStrategyService vcbStrategyService;
    private final PullbackRejectionScalpStrategyService pullbackRejectionScalpStrategyService;
    private final LsrStrategyService lsrStrategyService;
    private final CtrStrategyService ctrStrategyService;

    public StrategyExecutor get(String strategyName) {
        log.info("Getting strategy executor for strategy: {}", strategyName);
        return switch (strategyName) {
            case "TREND_PULLBACK_SINGLE_EXIT" -> trendPullbackSingleExitStrategyService;
            case "TEST" -> executionTestService;
            case "VCB" -> vcbStrategyService;
            case "PULLBACK_REJECTION_SCALP" -> pullbackRejectionScalpStrategyService;
            case "SCALP_MOM_V1" -> scalpMomV1StrategyService;
            case "LSR" -> lsrStrategyService;
            case "CTR_4H" -> ctrStrategyService;
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategyName);
        };
    }
}
