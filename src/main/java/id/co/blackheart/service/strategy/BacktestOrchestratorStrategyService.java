package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.strategy.StrategyRequirements;
import id.co.blackheart.util.TradeConstant.DecisionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestOrchestratorStrategyService implements StrategyExecutor {

    private static final String STRATEGY_CODE = "ORCHESTRATOR_AB";
    private static final String STRATEGY_NAME = "Backtest Orchestrator A/B";

    private static final String LSR_CODE = "LSR_ADAPTIVE_V5";
    private static final String VCB_CODE = "VCB";

    private final LsrV2StrategyService lsrV2StrategyService;
    private final VcbStrategyService vcbStrategyService;

    @Override
    public StrategyRequirements getRequirements() {

        return StrategyRequirements.builder()
                .requireBiasTimeframe(true)
                .biasInterval("4h")
                .requireRegimeSnapshot(true)
                .requireVolatilitySnapshot(true)
                .requireRiskSnapshot(true)
                .requireMarketQualitySnapshot(true)
                .requirePreviousFeatureStore(true)
                .build();
    }

    @Override
    public StrategyDecision execute(EnrichedStrategyContext context) {
        log.info("start Orchestrator strategy execution");
        if (context == null) {
            return hold("Context is null", null);
        }

        PositionSnapshot snapshot = context.getPositionSnapshot();

        if (hasOpenPosition(context, snapshot)) {
            return evaluateForActiveTrade(context, snapshot);
        }

        return evaluateForNewEntry(context);
    }

    private StrategyDecision evaluateForActiveTrade(EnrichedStrategyContext context, PositionSnapshot snapshot) {
        String ownerStrategyCode = resolveOwnerStrategyCode(context, snapshot);

        if (LSR_CODE.equalsIgnoreCase(ownerStrategyCode)) {
            log.debug("Active trade belongs to LSR, delegating management to LSR");
            return safeExecuteLsr(context, "ACTIVE_TRADE_LSR");
        }

        if (VCB_CODE.equalsIgnoreCase(ownerStrategyCode)) {
            log.debug("Active trade belongs to VCB, delegating management to VCB");
            return safeExecuteVcb(context, "ACTIVE_TRADE_VCB");
        }

        log.warn("Active trade exists but owner strategy could not be resolved, tradeId={}, owner={}",
                snapshot != null ? snapshot.getTradeId() : null,
                ownerStrategyCode);

        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(context.getInterval())
                .reason("Active trade exists but owner strategy could not be resolved")
                .tradeId(snapshot != null ? snapshot.getTradeId() : null)
                .tradePositionId(snapshot != null ? snapshot.getTradePositionId() : null)
                .build();
    }

    private StrategyDecision evaluateForNewEntry(EnrichedStrategyContext context) {
        StrategyDecision lsrDecision = safeExecuteLsr(context, "ENTRY_SCAN_LSR");
        if (isEntryDecision(lsrDecision)) {
            enrichWinningEntry(lsrDecision, LSR_CODE);
            return lsrDecision;
        }

        StrategyDecision vcbDecision = safeExecuteVcb(context, "ENTRY_SCAN_VCB");
        if (isEntryDecision(vcbDecision)) {
            enrichWinningEntry(vcbDecision, VCB_CODE);
            return vcbDecision;
        }

        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(context.getInterval())
                .reason("No entry signal from LSR_ADAPTIVE_V5 or VCB")
                .build();
    }

    private StrategyDecision safeExecuteLsr(EnrichedStrategyContext context, String phase) {
        try {
            StrategyDecision decision = lsrV2StrategyService.execute(context);
            if (decision == null) {
                return hold("LSR returned null during " + phase, context);
            }
            return decision;
        } catch (Exception ex) {
            log.error("Strategy execution failed, strategy={}, phase={}", LSR_CODE, phase, ex);
            return hold("LSR execution failed during " + phase + ": " + ex.getMessage(), context);
        }
    }

    private StrategyDecision safeExecuteVcb(EnrichedStrategyContext context, String phase) {
        try {
            StrategyDecision decision = vcbStrategyService.execute(context);
            if (decision == null) {
                return hold("VCB returned null during " + phase, context);
            }
            return decision;
        } catch (Exception ex) {
            log.error("Strategy execution failed, strategy={}, phase={}", VCB_CODE, phase, ex);
            return hold("VCB execution failed during " + phase + ": " + ex.getMessage(), context);
        }
    }

    private boolean hasOpenPosition(EnrichedStrategyContext context, PositionSnapshot snapshot) {
        if (context.hasTradablePosition()) {
            return true;
        }

        return snapshot != null
                && snapshot.isHasOpenPosition()
                && snapshot.getTradeId() != null;
    }

    private boolean isEntryDecision(StrategyDecision decision) {
        if (decision == null || decision.getDecisionType() == null) {
            return false;
        }

        return decision.getDecisionType() == DecisionType.OPEN_LONG
                || decision.getDecisionType() == DecisionType.OPEN_SHORT;
    }

    private void enrichWinningEntry(StrategyDecision decision, String strategyCode) {
        if (!StringUtils.hasText(decision.getStrategyCode())) {
            decision.setStrategyCode(strategyCode);
        }
    }

    private StrategyDecision hold(String reason, EnrichedStrategyContext context) {
        return StrategyDecision.builder()
                .decisionType(DecisionType.HOLD)
                .strategyCode(STRATEGY_CODE)
                .strategyName(STRATEGY_NAME)
                .strategyInterval(context != null ? context.getInterval() : null)
                .reason(reason)
                .build();
    }

    private String resolveOwnerStrategyCode(EnrichedStrategyContext context, PositionSnapshot snapshot) {

        Object tradeHolder = invokeNoArg(context, "getCurrentTrade");
        if (tradeHolder == null) tradeHolder = invokeNoArg(context, "getOpenTrade");
        if (tradeHolder == null) tradeHolder = invokeNoArg(context, "getTrade");
        if (tradeHolder == null) tradeHolder = invokeNoArg(context, "getActiveTrade");

        String strategyCode = extractStrategyCode(tradeHolder);
        if (StringUtils.hasText(strategyCode)) {
            return strategyCode;
        }

        String strategyName = extractStrategyName(tradeHolder);
        if (VCB_CODE.equalsIgnoreCase(strategyName) || LSR_CODE.equalsIgnoreCase(strategyName)) {
            return strategyName;
        }

        if (snapshot != null && StringUtils.hasText(snapshot.getPositionRole())) {
            String role = snapshot.getPositionRole().toUpperCase();
            if (role.contains("RUNNER") || role.contains("TP1") || role.contains("TP2")) {
                return VCB_CODE;
            }
        }

        return null;
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractStrategyCode(Object tradeHolder) {
        if (tradeHolder == null) return null;

        Object value = invokeNoArg(tradeHolder, "getStrategyCode");
        if (value instanceof String s && StringUtils.hasText(s)) {
            return s;
        }

        value = invokeNoArg(tradeHolder, "getStrategyName");
        if (value instanceof String s && StringUtils.hasText(s)) {
            if (VCB_CODE.equalsIgnoreCase(s) || LSR_CODE.equalsIgnoreCase(s)) {
                return s;
            }
        }

        return null;
    }

    private String extractStrategyName(Object tradeHolder) {
        if (tradeHolder == null) return null;

        Object value = invokeNoArg(tradeHolder, "getStrategyName");
        if (value instanceof String s && StringUtils.hasText(s)) {
            return s;
        }

        return null;
    }
}