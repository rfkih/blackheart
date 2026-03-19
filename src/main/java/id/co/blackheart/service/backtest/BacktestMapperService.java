package id.co.blackheart.service.backtest;

import id.co.blackheart.dto.response.BacktestRunResponse;
import id.co.blackheart.model.BacktestRun;
import org.springframework.stereotype.Service;

@Service
public class BacktestMapperService {

    public BacktestRunResponse toRunResponse(BacktestRun run) {
        return BacktestRunResponse.builder()
                .backtestRunId(run.getBacktestRunId())
                .userId(run.getUserId())
                .runName(run.getRunName())
                .strategyName(run.getStrategyName())
                .symbol(run.getSymbol())
                .interval(run.getInterval())
                .status(run.getStatus())
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .initialCapital(run.getInitialCapital())
                .finalCapital(run.getFinalCapital())
                .feeRate(run.getFeeRate())
                .slippageRate(run.getSlippageRate())
                .allowLong(run.getAllowLong())
                .allowShort(run.getAllowShort())
                .maxOpenPositions(run.getMaxOpenPositions())
                .totalTrades(run.getTotalTrades())
                .winningTrades(run.getWinningTrades())
                .losingTrades(run.getLosingTrades())
                .winRate(run.getWinRate())
                .profitFactor(run.getProfitFactor())
                .maxDrawdownPercent(run.getMaxDrawdownPercent())
                .totalReturnPercent(run.getTotalReturnPercent())
                .sharpeRatio(run.getSharpeRatio())
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .build();
    }
}