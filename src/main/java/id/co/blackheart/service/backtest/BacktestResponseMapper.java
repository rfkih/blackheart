package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.response.BacktestRunResponse;
import id.co.blackheart.model.BacktestRun;
import org.springframework.stereotype.Service;

@Service
public class BacktestResponseMapper {

    public BacktestRunResponse toRunResponse(BacktestRun run) {
        return BacktestRunResponse.builder()
                .backtestRunId(run.getBacktestRunId())
                .userStrategyId(run.getUserStrategyId())
                .strategyName(run.getStrategyName())
                .asset(run.getAsset())
                .interval(run.getInterval())
                .status(run.getStatus())
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .initialCapital(run.getInitialCapital())
                .endingBalance(run.getEndingBalance())
                .riskPerTradePct(run.getRiskPerTradePct())
                .feePct(run.getFeePct())
                .slippagePct(run.getSlippagePct())
                .minNotional(run.getMinNotional())
                .minQty(run.getMinQty())
                .qtyStep(run.getQtyStep())
                .totalTrades(run.getTotalTrades())
                .totalWins(run.getTotalWins())
                .totalLosses(run.getTotalLosses())
                .winRate(run.getWinRate())
                .grossProfit(run.getGrossProfit())
                .grossLoss(run.getGrossLoss())
                .netProfit(run.getNetProfit())
                .maxDrawdownPct(run.getMaxDrawdownPct())
                .createdTime(run.getCreatedTime())
                .updatedTime(run.getUpdatedTime())
                .build();
    }
}