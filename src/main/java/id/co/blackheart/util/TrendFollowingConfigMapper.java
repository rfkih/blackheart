package id.co.blackheart.util;


import id.co.blackheart.dto.strategy.TrendFollowingStrategyConfigDto;
import id.co.blackheart.model.StrategyConfig;
import id.co.blackheart.model.TrendFollowingConfigDetail;

public final class TrendFollowingConfigMapper {

    private TrendFollowingConfigMapper() {
    }

    public static TrendFollowingStrategyConfigDto toDto(
            StrategyConfig strategyConfig,
            TrendFollowingConfigDetail detail
    ) {
        return TrendFollowingStrategyConfigDto.builder()
                .strategyConfigId(strategyConfig.getStrategyConfigId())
                .trendFollowingConfigDetailId(detail.getTrendFollowingConfigDetailId())
                .strategyName(strategyConfig.getStrategyName())
                .intervalName(strategyConfig.getIntervalName())
                .symbol(strategyConfig.getSymbol())
                .status(strategyConfig.getStatus())
                .version(strategyConfig.getVersion())
                .enabled(strategyConfig.getEnabled())
                .minAdx(detail.getMinAdx())
                .minEfficiencyRatio(detail.getMinEfficiencyRatio())
                .minRelativeVolume(detail.getMinRelativeVolume())
                .stopAtrMultiplier(detail.getStopAtrMultiplier())
                .takeProfitAtrMultiplier(detail.getTakeProfitAtrMultiplier())
                .trailingAtrMultiplier(detail.getTrailingAtrMultiplier())
                .allowLong(detail.getAllowLong())
                .allowShort(detail.getAllowShort())
                .allowBreakoutEntry(detail.getAllowBreakoutEntry())
                .allowPullbackEntry(detail.getAllowPullbackEntry())
                .allowBiasEntry(detail.getAllowBiasEntry())
                .build();
    }
}
