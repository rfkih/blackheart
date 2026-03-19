package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.backtest.BacktestState;
import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.repository.BacktestTradeRepository;
import id.co.blackheart.util.TradeConstant.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestTradeExecutorService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";

    private final BacktestTradeRepository backtestTradeRepository;
    private final BacktestPricingService backtestPricingService;

    public void execute(
            BacktestRun backtestRun,
            BacktestState state,
            StrategyContext context,
            StrategyDecision decision
    ) {
        if (decision == null || DecisionType.HOLD.equals(decision.getDecisionType())) {
            return;
        }

        switch (decision.getDecisionType()) {
            case OPEN_LONG -> openLong(backtestRun, state, context, decision);
            case OPEN_SHORT -> openShort(backtestRun, state, context, decision);
            case CLOSE_LONG, CLOSE_SHORT -> closeTrade(backtestRun, state, context, decision);
            case UPDATE_TRAILING_STOP -> updateTrailingStop(state, decision);
            default -> log.debug("Unhandled decisionType={}", decision.getDecisionType());
        }
    }


    public void closeTradeFromListener(
            BacktestRun run,
            BacktestState state,
            StrategyContext context,
            String exitReason,
            BigDecimal exitPrice
    ) {
        BacktestTrade activeTrade = state.getActiveTrade();
        if (activeTrade == null) {
            return;
        }

        BigDecimal exitQuoteQty = activeTrade.getEntryQty().multiply(exitPrice);
        BigDecimal exitFee = exitQuoteQty.multiply(run.getFeeRate());

        BigDecimal plAmount;
        if ("LONG".equalsIgnoreCase(activeTrade.getSide())) {
            plAmount = exitQuoteQty
                    .subtract(activeTrade.getEntryQuoteQty())
                    .subtract(activeTrade.getEntryFee() == null ? BigDecimal.ZERO : activeTrade.getEntryFee())
                    .subtract(exitFee);
        } else {
            plAmount = activeTrade.getEntryQuoteQty()
                    .subtract(exitQuoteQty)
                    .subtract(activeTrade.getEntryFee() == null ? BigDecimal.ZERO : activeTrade.getEntryFee())
                    .subtract(exitFee);
        }

        BigDecimal plPercent = BigDecimal.ZERO;
        if (activeTrade.getEntryQuoteQty() != null && activeTrade.getEntryQuoteQty().signum() > 0) {
            plPercent = plAmount
                    .divide(activeTrade.getEntryQuoteQty(), 8, java.math.RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        activeTrade.setStatus("CLOSED");
        activeTrade.setExitTime(context.getMarketData().getEndTime());
        activeTrade.setExitPrice(exitPrice);
        activeTrade.setExitQty(activeTrade.getEntryQty());
        activeTrade.setExitQuoteQty(exitQuoteQty);
        activeTrade.setExitFee(exitFee);
        activeTrade.setExitReason(exitReason);
        activeTrade.setPlAmount(plAmount);
        activeTrade.setPlPercent(plPercent);

        activeTrade = backtestTradeRepository.save(activeTrade);

        state.setCashBalance(activeTrade.getEntryQuoteQty().add(plAmount));
        state.getCompletedTrades().add(activeTrade);
        state.setActiveTrade(null);
    }

    private void openLong(BacktestRun run, BacktestState state, StrategyContext context, StrategyDecision decision) {
        if (state.getActiveTrade() != null) {
            return;
        }

        BigDecimal entryPrice = backtestPricingService.applyEntrySlippage(
                context.getMarketData().getClosePrice(),
                run.getSlippageRate(),
                "LONG"
        );

        BigDecimal entryQuoteQty = state.getCashBalance();
        if (entryQuoteQty == null || entryQuoteQty.signum() <= 0) {
            return;
        }

        BigDecimal entryQty = entryQuoteQty.divide(entryPrice, 12, BigDecimal.ROUND_DOWN);
        BigDecimal entryFee = entryQuoteQty.multiply(run.getFeeRate());

        BacktestTrade trade = BacktestTrade.builder()
                .backtestRunId(run.getBacktestRunId())
                .userId(run.getUserId())
                .strategyName(run.getStrategyName())
                .interval(run.getInterval())
                .asset(run.getSymbol())
                .side("LONG")
                .status(STATUS_OPEN)
                .entryTime(context.getMarketData().getEndTime())
                .entryPrice(entryPrice)
                .entryQty(entryQty)
                .entryQuoteQty(entryQuoteQty)
                .entryFee(entryFee)
                .initialStopLossPrice(decision.getStopLossPrice())
                .currentStopLossPrice(decision.getStopLossPrice())
                .trailingStopPrice(decision.getTrailingStopPrice())
                .takeProfitPrice(decision.getTakeProfitPrice())
                .entryTrendRegime(decision.getEntryTrendRegime())
                .entryAdx(decision.getEntryAdx())
                .entryAtr(decision.getEntryAtr())
                .entryRsi(decision.getEntryRsi())
                .createdAt(LocalDateTime.now())
                .build();

        trade = backtestTradeRepository.save(trade);
        state.setActiveTrade(trade);
        state.setCashBalance(BigDecimal.ZERO);
    }

    private void openShort(BacktestRun run, BacktestState state, StrategyContext context, StrategyDecision decision) {
        if (state.getActiveTrade() != null) {
            return;
        }

        BigDecimal entryPrice = backtestPricingService.applyEntrySlippage(
                context.getMarketData().getClosePrice(),
                run.getSlippageRate(),
                "SHORT"
        );

        BigDecimal entryQuoteQty = state.getCashBalance();
        if (entryQuoteQty == null || entryQuoteQty.signum() <= 0) {
            return;
        }

        BigDecimal entryQty = entryQuoteQty.divide(entryPrice, 12, BigDecimal.ROUND_DOWN);
        BigDecimal entryFee = entryQuoteQty.multiply(run.getFeeRate());

        BacktestTrade trade = BacktestTrade.builder()
                .backtestRunId(run.getBacktestRunId())
                .userId(run.getUserId())
                .strategyName(run.getStrategyName())
                .interval(run.getInterval())
                .asset(run.getSymbol())
                .side("SHORT")
                .status(STATUS_OPEN)
                .entryTime(context.getMarketData().getEndTime())
                .entryPrice(entryPrice)
                .entryQty(entryQty)
                .entryQuoteQty(entryQuoteQty)
                .entryFee(entryFee)
                .initialStopLossPrice(decision.getStopLossPrice())
                .currentStopLossPrice(decision.getStopLossPrice())
                .trailingStopPrice(decision.getTrailingStopPrice())
                .takeProfitPrice(decision.getTakeProfitPrice())
                .entryTrendRegime(decision.getEntryTrendRegime())
                .entryAdx(decision.getEntryAdx())
                .entryAtr(decision.getEntryAtr())
                .entryRsi(decision.getEntryRsi())
                .createdAt(LocalDateTime.now())
                .build();

        trade = backtestTradeRepository.save(trade);
        state.setActiveTrade(trade);
        state.setCashBalance(BigDecimal.ZERO);
    }

    private void closeTrade(BacktestRun run, BacktestState state, StrategyContext context, StrategyDecision decision) {
        BacktestTrade activeTrade = state.getActiveTrade();
        if (activeTrade == null) {
            return;
        }

        BigDecimal exitPrice = backtestPricingService.applyExitSlippage(
                context.getMarketData().getClosePrice(),
                run.getSlippageRate(),
                activeTrade.getSide()
        );

        BigDecimal exitQuoteQty = activeTrade.getEntryQty().multiply(exitPrice);
        BigDecimal exitFee = exitQuoteQty.multiply(run.getFeeRate());

        BigDecimal plAmount;
        if ("LONG".equalsIgnoreCase(activeTrade.getSide())) {
            plAmount = exitQuoteQty
                    .subtract(activeTrade.getEntryQuoteQty())
                    .subtract(activeTrade.getEntryFee() == null ? BigDecimal.ZERO : activeTrade.getEntryFee())
                    .subtract(exitFee);
        } else {
            plAmount = activeTrade.getEntryQuoteQty()
                    .subtract(exitQuoteQty)
                    .subtract(activeTrade.getEntryFee() == null ? BigDecimal.ZERO : activeTrade.getEntryFee())
                    .subtract(exitFee);
        }

        BigDecimal plPercent = BigDecimal.ZERO;
        if (activeTrade.getEntryQuoteQty() != null && activeTrade.getEntryQuoteQty().signum() > 0) {
            plPercent = plAmount
                    .divide(activeTrade.getEntryQuoteQty(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        activeTrade.setStatus(STATUS_CLOSED);
        activeTrade.setExitTime(context.getMarketData().getEndTime());
        activeTrade.setExitPrice(exitPrice);
        activeTrade.setExitQty(activeTrade.getEntryQty());
        activeTrade.setExitQuoteQty(exitQuoteQty);
        activeTrade.setExitFee(exitFee);
        activeTrade.setExitReason(decision.getExitReason());
        activeTrade.setPlAmount(plAmount);
        activeTrade.setPlPercent(plPercent);

        activeTrade = backtestTradeRepository.save(activeTrade);

        state.setCashBalance(activeTrade.getEntryQuoteQty().add(plAmount));
        state.getCompletedTrades().add(activeTrade);
        state.setActiveTrade(null);
    }

    private void updateTrailingStop(BacktestState state, StrategyDecision decision) {
        BacktestTrade activeTrade = state.getActiveTrade();
        if (activeTrade == null) {
            return;
        }

        activeTrade.setTrailingStopPrice(decision.getTrailingStopPrice());
        activeTrade.setCurrentStopLossPrice(decision.getStopLossPrice());
        backtestTradeRepository.save(activeTrade);
    }
}