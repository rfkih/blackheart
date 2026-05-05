package id.co.blackheart.service.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.StrategyDefinitionRepository;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.service.portfolio.PortfolioService;
import id.co.blackheart.service.promotion.StrategyPromotionService;
import id.co.blackheart.service.risk.BookVolTargetingService;
import id.co.blackheart.service.risk.KellySizingService;
import id.co.blackheart.service.risk.RiskGuardService;
import id.co.blackheart.service.strategy.StrategyDecisionInvariants;
import id.co.blackheart.service.trade.TradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import id.co.blackheart.util.SymbolUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradingDecisionExecutorService {

    private static final BigDecimal MIN_USDT_NOTIONAL = new BigDecimal("7");
    private static final BigDecimal MIN_BTC_NOTIONAL = new BigDecimal("0.00001");

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_PARTIALLY_CLOSED = "PARTIALLY_CLOSED";

    private static final String SIDE_LONG = "LONG";
    private static final String SIDE_SHORT = "SHORT";
    private static final String EXIT_REASON_MANUAL_CLOSE = "MANUAL_CLOSE";
    private static final String EXIT_REASON_STRATEGY_EXIT = "STRATEGY_EXIT";

    private final TradePositionRepository tradePositionRepository;
    private final PortfolioService portfolioService;
    private final TradeService tradeService;
    private final RiskGuardService riskGuardService;
    private final KellySizingService kellySizingService;
    private final BookVolTargetingService bookVolTargetingService;
    private final StrategyPromotionService strategyPromotionService;
    private final StrategyDefinitionRepository strategyDefinitionRepository;

    public void execute(
            Trades activeTrade,
            EnrichedStrategyContext context,
            StrategyDecision decision
    ) throws JsonProcessingException {
        if (decision == null || decision.getDecisionType() == null || context == null) {
            return;
        }

        // Log-only guard. Catches contract violations like the LSR/VCB SHORT
        // sizing bug at the boundary between strategy and executor — without
        // blocking the live loop while we build confidence in the validator.
        BigDecimal entryRef = context.getMarketData() != null
                ? context.getMarketData().getClosePrice()
                : null;
        List<String> violations = StrategyDecisionInvariants.validate(decision, entryRef);
        if (!violations.isEmpty()) {
            log.warn("[StrategyInvariant] {} {} violations: {}",
                    decision.getStrategyCode(), decision.getDecisionType(), violations);
        }

        // Risk guard: rolling-DD kill switch + per-account concurrency cap.
        // Only gates new entries; CLOSE_*/UPDATE_* always pass through so
        // we can still get out of existing positions even when tripped.
        if (decision.getDecisionType() == id.co.blackheart.util.TradeConstant.DecisionType.OPEN_LONG
                || decision.getDecisionType() == id.co.blackheart.util.TradeConstant.DecisionType.OPEN_SHORT) {
            UUID accountStrategyId = decision.getAccountStrategyId();
            if (accountStrategyId == null && context.getAccountStrategy() != null) {
                accountStrategyId = context.getAccountStrategy().getAccountStrategyId();
            }
            if (accountStrategyId != null) {
                String side = decision.getDecisionType() == id.co.blackheart.util.TradeConstant.DecisionType.OPEN_LONG
                        ? SIDE_LONG : SIDE_SHORT;
                RiskGuardService.GuardVerdict verdict = riskGuardService.canOpen(
                        accountStrategyId, side, context.getFeatureStore());
                if (!verdict.allowed()) {
                    log.warn("[RiskGuard] BLOCKED {} | strategy={} reason={}",
                            decision.getDecisionType(), decision.getStrategyCode(), verdict.reason());
                    return;
                }

                // Capture intent BEFORE vol-targeting mutates, so realized P&L
                // can later be decomposed into signal-alpha / exec-drift /
                // sizing-residual at close.
                captureIntent(side, decision, context);

                // PSR-discounted half-Kelly multiplier from recent qualifying
                // backtest runs. Off by default; when off returns 1.0.
                // UUID passed alongside entity so the service can fall back to
                // a DB lookup when context.getAccountStrategy() is null.
                applyKellySizing(context.getAccountStrategy(), accountStrategyId, side, decision);

                // Scales computed size so realized vol hits target and
                // correlated bets shrink. Off by default per Account.
                applyVolTargeting(accountStrategyId, side, decision);
            }
        }

        // Paper/simulated gate — if EITHER scope says simulated
        // (definition.simulated OR account_strategy.simulated), record the
        // intent into paper_trade_run and short-circuit before real-order code.
        // Fail-safe: either scope says paper → paper wins.
        //
        // CRITICAL: only OPEN_LONG / OPEN_SHORT are diverted. CLOSE_* and
        // UPDATE_POSITION_MANAGEMENT MUST always fall through to real execution.
        // If an emergency demote stranded a live position, its close signal
        // would be recorded as a paper trade and the real position would never
        // close. An operator demoting a promoted strategy with open positions
        // blocks new entries immediately; existing legs wind down via the
        // strategy's own exit logic. Use TradeController to force-flatten faster.
        boolean isOpenAction =
                decision.getDecisionType() == id.co.blackheart.util.TradeConstant.DecisionType.OPEN_LONG
                        || decision.getDecisionType() == id.co.blackheart.util.TradeConstant.DecisionType.OPEN_SHORT;
        if (isOpenAction && context.getAccountStrategy() != null) {
            boolean accountSimulated =
                    Boolean.TRUE.equals(context.getAccountStrategy().getSimulated());
            var defOpt = strategyDefinitionRepository.findByStrategyCode(decision.getStrategyCode());
            boolean definitionDisabled = defOpt
                    .map(d -> Boolean.FALSE.equals(d.getEnabled()))
                    .orElse(false);
            boolean definitionSimulated = defOpt
                    .map(d -> Boolean.TRUE.equals(d.getSimulated()))
                    .orElse(false);

            // Kill-switch: definition disabled → drop entry silently (no paper
            // trade either). CLOSE_*/UPDATE bypass this block entirely.
            if (definitionDisabled) {
                log.info("[KillSwitch] {} {} — definition disabled, dropping entry signal",
                        decision.getStrategyCode(), decision.getDecisionType());
                return;
            }
            if (accountSimulated || definitionSimulated) {
                UUID accountStrategyId = context.getAccountStrategy().getAccountStrategyId();
                log.info("[PaperTrade] {} {} — simulated (def={}, acct={}), recording without placing order",
                        decision.getStrategyCode(), decision.getDecisionType(),
                        definitionSimulated, accountSimulated);
                strategyPromotionService.recordPaperTrade(accountStrategyId, decision, context);
                return;
            }
        }

        switch (decision.getDecisionType()) {
            case OPEN_LONG -> executeOpenLong(context, decision);
            case OPEN_SHORT -> executeOpenShort(context, decision);
            case UPDATE_POSITION_MANAGEMENT -> executeUpdatePositionManagement(activeTrade, decision);
            case CLOSE_LONG -> executeCloseTrade(activeTrade, context.getAccount(), decision.getExitReason());
            case CLOSE_SHORT -> executeCloseTrade(activeTrade, context.getAccount(), decision.getExitReason());
            case HOLD -> log.debug("No execution for HOLD");
            default -> log.debug("Decision type not handled yet: {}", decision.getDecisionType());
        }
    }

    public void executeListenerClosePosition(
            Account account,
            TradePosition activeTradePosition,
            String asset,
            ListenerDecision listenerDecision
    ) throws JsonProcessingException {
        if (activeTradePosition == null || listenerDecision == null || !listenerDecision.isTriggered()) {
            return;
        }

        activeTradePosition.setExitReason(listenerDecision.getExitReason());
        tradePositionRepository.save(activeTradePosition);

        if (SIDE_LONG.equalsIgnoreCase(activeTradePosition.getSide())) {
            tradeService.binanceCloseLongPositionMarketOrder(account, activeTradePosition, asset);
        } else if (SIDE_SHORT.equalsIgnoreCase(activeTradePosition.getSide())) {
            tradeService.binanceCloseShortPositionMarketOrder(account, activeTradePosition, asset);
        } else {
            log.warn(
                    "Unknown side for listener close | tradePositionId={} side={}",
                    activeTradePosition.getTradePositionId(),
                    activeTradePosition.getSide()
            );
        }
    }

    public void executeManualClosePosition(Account account, UUID tradePositionId) throws JsonProcessingException {
        TradePosition tradePosition = tradePositionRepository.findByTradePositionId(tradePositionId).orElse(null);
        if (tradePosition == null || !STATUS_OPEN.equalsIgnoreCase(tradePosition.getStatus())) {
            return;
        }

        tradePosition.setExitReason(EXIT_REASON_MANUAL_CLOSE);
        tradePositionRepository.save(tradePosition);

        if (SIDE_LONG.equalsIgnoreCase(tradePosition.getSide())) {
            tradeService.binanceCloseLongPositionMarketOrder(account, tradePosition, tradePosition.getAsset());
        } else if (SIDE_SHORT.equalsIgnoreCase(tradePosition.getSide())) {
            tradeService.binanceCloseShortPositionMarketOrder(account, tradePosition, tradePosition.getAsset());
        } else {
            log.warn(
                    "Unknown side for manual close | tradePositionId={} side={}",
                    tradePosition.getTradePositionId(),
                    tradePosition.getSide()
            );
        }
    }

    public void executeListenerClosePositions(
            Account account,
            List<TradePosition> activeTradePositions,
            String asset,
            ListenerDecision listenerDecision
    ) throws JsonProcessingException {
        if (activeTradePositions == null || activeTradePositions.isEmpty()
                || listenerDecision == null || !listenerDecision.isTriggered()) {
            return;
        }

        TradePosition firstPosition = activeTradePositions.getFirst();

        for (TradePosition tradePosition : activeTradePositions) {
            tradePosition.setExitReason(listenerDecision.getExitReason());
        }
        tradePositionRepository.saveAll(activeTradePositions);

        if (SIDE_LONG.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseLongPositionsMarketOrder(account, activeTradePositions, asset);
        } else if (SIDE_SHORT.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseShortPositionsMarketOrder(account, activeTradePositions, asset);
        } else {
            log.warn(
                    "Unknown side for grouped listener close | tradeId={} side={}",
                    firstPosition.getTradeId(),
                    firstPosition.getSide()
            );
        }
    }

    public void executeManualCloseTrade(Account account, UUID tradeId) throws JsonProcessingException {
        List<TradePosition> openPositions = tradePositionRepository.findAllByTradeIdAndStatus(tradeId, STATUS_OPEN);
        if (openPositions.isEmpty()) {
            return;
        }

        TradePosition firstPosition = openPositions.getFirst();

        for (TradePosition tradePosition : openPositions) {
            tradePosition.setExitReason(EXIT_REASON_MANUAL_CLOSE);
        }
        tradePositionRepository.saveAll(openPositions);

        if (SIDE_LONG.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseLongPositionsMarketOrder(account, openPositions, firstPosition.getAsset());
        } else if (SIDE_SHORT.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseShortPositionsMarketOrder(account, openPositions, firstPosition.getAsset());
        } else {
            log.warn(
                    "Unknown side for manual trade close | tradeId={} side={}",
                    tradeId,
                    firstPosition.getSide()
            );
        }
    }

    private void executeOpenLong(
            EnrichedStrategyContext context,
            StrategyDecision decision
    ) throws JsonProcessingException {
        Account account = context.getAccount();
        String quoteAsset = SymbolUtils.quoteAsset(context.getAsset());

        Portfolio quotePortfolio = portfolioService.updateAndGetAssetBalance(quoteAsset, account);
        BigDecimal balance = quotePortfolio == null ? BigDecimal.ZERO : safe(quotePortfolio.getBalance());

        BigDecimal tradeAmount = decision.getNotionalSize() != null
                ? decision.getNotionalSize()
                : calculateLongTradeAmount(balance, account, context.getAccountStrategy());

        if (tradeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Invalid LONG trade amount. tradeAmount={}", tradeAmount);
            return;
        }

        if (balance.compareTo(tradeAmount) < 0) {
            log.info(
                    "Insufficient {} balance for LONG entry | balance={} required={}",
                    quoteAsset,
                    balance,
                    tradeAmount
            );
            return;
        }

        if ("BNC".equalsIgnoreCase(account.getExchange())) {
            tradeService.binanceOpenLongMarketOrder(context, decision, tradeAmount);
            return;
        }

        log.warn("Unsupported exchange for LONG entry: {}", account.getExchange());
    }

    private void executeOpenShort(
            EnrichedStrategyContext context,
            StrategyDecision decision
    ) throws JsonProcessingException {
        Account account = context.getAccount();
        String baseAsset = SymbolUtils.baseAsset(context.getAsset());

        Portfolio basePortfolio = portfolioService.updateAndGetAssetBalance(baseAsset, account);
        BigDecimal balance = basePortfolio == null ? BigDecimal.ZERO : safe(basePortfolio.getBalance());

        BigDecimal tradeAmount = decision.getPositionSize() != null
                ? decision.getPositionSize()
                : calculateShortTradeAmount(balance, account, context.getAccountStrategy());

        if (tradeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Invalid SHORT trade amount. tradeAmount={}", tradeAmount);
            return;
        }

        if (balance.compareTo(tradeAmount) < 0) {
            log.info(
                    "Insufficient {} balance for SHORT entry | balance={} required={}",
                    baseAsset,
                    balance,
                    tradeAmount
            );
            return;
        }

        log.info(
                "SHORT entry sizing | baseAsset={} balance={} riskAmount={} tradeBaseQty={}",
                baseAsset,
                balance,
                account.getRiskAmount(),
                tradeAmount
        );

        if ("BNC".equalsIgnoreCase(account.getExchange())) {
            tradeService.binanceOpenShortMarketOrder(context, decision, tradeAmount, context.getAsset());
            return;
        }

        log.warn("Unsupported exchange for SHORT entry: {}", account.getExchange());
    }

    private void executeUpdatePositionManagement(Trades activeTrade, StrategyDecision decision) {
        if (activeTrade == null || decision == null) {
            return;
        }

        tradeService.updateOpenTradePositions(activeTrade, decision);
    }

    private void executeCloseTrade(Trades activeTrade, Account account, String exitReason) throws JsonProcessingException {
        if (activeTrade == null || account == null) {
            return;
        }

        List<TradePosition> openPositions =
                tradePositionRepository.findAllByTradeIdAndStatus(activeTrade.getTradeId(), STATUS_OPEN);

        if (openPositions.isEmpty()) {
            return;
        }

        TradePosition firstPosition = openPositions.getFirst();

        for (TradePosition tradePosition : openPositions) {
            tradePosition.setExitReason(exitReason != null ? exitReason : EXIT_REASON_STRATEGY_EXIT);
        }
        tradePositionRepository.saveAll(openPositions);

        if (SIDE_LONG.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseLongPositionsMarketOrder(account, openPositions, firstPosition.getAsset());
        } else if (SIDE_SHORT.equalsIgnoreCase(firstPosition.getSide())) {
            tradeService.binanceCloseShortPositionsMarketOrder(account, openPositions, firstPosition.getAsset());
        } else {
            log.warn(
                    "Unknown side for close trade | tradeId={} side={}",
                    activeTrade.getTradeId(),
                    firstPosition.getSide()
            );
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Stash decision intent on the decision so TradeOpenService can persist it.
     * Called BEFORE {@link #applyVolTargeting} so the size captured here is the
     * strategy's pre-targeting intent, not the scaled result. Idempotent —
     * strategies that already set intended* are left alone.
     */
    private void captureIntent(String side, StrategyDecision decision, EnrichedStrategyContext context) {
        if (decision.getIntendedSize() == null) {
            BigDecimal currentSize = SIDE_LONG.equalsIgnoreCase(side)
                    ? decision.getNotionalSize()
                    : decision.getPositionSize();
            decision.setIntendedSize(currentSize);
        }
        if (decision.getIntendedEntryPrice() == null
                && context.getMarketData() != null) {
            decision.setIntendedEntryPrice(context.getMarketData().getClosePrice());
        }
    }

    /**
     * Apply PSR-discounted half-Kelly multiplier to the entry size in-place.
     * LONG scales {@code notionalSize}; SHORT scales {@code positionSize}.
     * Returns without mutating when Kelly is disabled or returns 1.0 (no-op).
     *
     * <p>Prefers the already-loaded {@code accountStrategy} (no DB hit). Falls
     * back to a {@code accountStrategyId}-based lookup when the entity is
     * null — without this fallback Kelly silently no-ops on any code path
     * that populates {@code decision.accountStrategyId} but leaves
     * {@code context.accountStrategy} unloaded.
     */
    private void applyKellySizing(id.co.blackheart.model.AccountStrategy accountStrategy,
                                  UUID accountStrategyId,
                                  String side, StrategyDecision decision) {
        BigDecimal baseSize = SIDE_LONG.equalsIgnoreCase(side)
                ? decision.getNotionalSize()
                : decision.getPositionSize();
        if (baseSize == null || baseSize.signum() <= 0) return;

        BigDecimal multiplier = accountStrategy != null
                ? kellySizingService.computeKellyMultiplier(accountStrategy)
                : kellySizingService.computeKellyMultiplier(accountStrategyId);
        if (multiplier.compareTo(BigDecimal.ONE) == 0) return;

        BigDecimal scaled = baseSize.multiply(multiplier).setScale(8, RoundingMode.HALF_UP);
        log.info("[Kelly] {} {} | base={} → scaled={} (multiplier={})",
                decision.getStrategyCode(), side, baseSize, scaled,
                multiplier.setScale(4, RoundingMode.HALF_UP));
        if (SIDE_LONG.equalsIgnoreCase(side)) {
            decision.setNotionalSize(scaled);
        } else {
            decision.setPositionSize(scaled);
        }
    }

    /**
     * Apply book vol-targeting to the entry size in-place on the decision.
     * LONG reads/writes {@code notionalSize} (USDT); SHORT reads/writes
     * {@code positionSize} (BTC). When the service returns the size
     * unchanged (toggle off, thin sample, missing capital baseline) the
     * decision is left alone.
     */
    private void applyVolTargeting(UUID accountStrategyId, String side, StrategyDecision decision) {
        BigDecimal baseSize = SIDE_LONG.equalsIgnoreCase(side)
                ? decision.getNotionalSize()
                : decision.getPositionSize();
        if (baseSize == null || baseSize.signum() <= 0) return;

        BookVolTargetingService.SizingScale result =
                bookVolTargetingService.scale(accountStrategyId, side, baseSize);
        if (result.scaledSize() == null
                || result.scaledSize().compareTo(baseSize) == 0) {
            return;
        }

        log.info("[VolTargeting] {} {} | base={} → scaled={} ({})",
                decision.getStrategyCode(), side, baseSize, result.scaledSize(), result.reason());
        if (SIDE_LONG.equalsIgnoreCase(side)) {
            decision.setNotionalSize(result.scaledSize());
        } else {
            decision.setPositionSize(result.scaledSize());
        }
    }

    /**
     * Fallback sizing when a strategy didn't populate {@code notionalSize}.
     * Single-knob: trade amount is {@code usdtBalance × allocFraction}.
     * No further per-trade-risk multiplier — capital_allocation_pct IS the
     * sizing knob (50 → 50% of balance per trade). Returns ZERO when no
     * allocation is set; the executor then skips the entry as "invalid
     * trade amount". MIN_USDT_NOTIONAL floor keeps Binance min-order rules
     * happy on small allocations.
     */
    private BigDecimal calculateLongTradeAmount(BigDecimal usdtBalance, Account account,
                                                id.co.blackheart.model.AccountStrategy accountStrategy) {
        if (usdtBalance == null) return BigDecimal.ZERO;
        BigDecimal alloc = allocFraction(accountStrategy);
        if (alloc.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal tradeAmount = usdtBalance.multiply(alloc).setScale(8, RoundingMode.DOWN);
        return tradeAmount.compareTo(MIN_USDT_NOTIONAL) < 0 ? MIN_USDT_NOTIONAL : tradeAmount;
    }

    private BigDecimal calculateShortTradeAmount(BigDecimal btcBalance, Account account,
                                                 id.co.blackheart.model.AccountStrategy accountStrategy) {
        if (btcBalance == null) return BigDecimal.ZERO;
        BigDecimal alloc = allocFraction(accountStrategy);
        if (alloc.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal tradeAmount = btcBalance.multiply(alloc).setScale(8, RoundingMode.DOWN);
        return tradeAmount.compareTo(MIN_BTC_NOTIONAL) < 0 ? MIN_BTC_NOTIONAL : tradeAmount;
    }

    /**
     * Strategy's allocation as a fraction in (0, 1]. Mirrors
     * {@code StrategyHelper.resolveCapitalAllocationFraction} — strict
     * semantics: null/zero → ZERO (no trade). The schema has
     * capital_allocation_pct NOT NULL so this should only happen on
     * legacy / partial rows.
     */
    private static BigDecimal allocFraction(id.co.blackheart.model.AccountStrategy as) {
        if (as == null) return BigDecimal.ZERO;
        BigDecimal alloc = as.getCapitalAllocationPct();
        if (alloc == null || alloc.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal capped = alloc.min(new BigDecimal("100"));
        return capped.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
    }
}