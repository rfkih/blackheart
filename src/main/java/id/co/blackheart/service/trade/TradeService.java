package id.co.blackheart.service.trade;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static id.co.blackheart.util.TradeConstant.TradeType;

@Service
@RequiredArgsConstructor
public class TradeService {

    private final TradeOpenService tradeOpenService;
    private final TradeCloseService tradeCloseService;

    @Transactional
    public void binanceOpenLongMarketOrder(
            EnrichedStrategyContext context,
            StrategyDecision decision,
            BigDecimal tradeAmount
    ) {
        tradeOpenService.openMarketOrder(
                context,
                decision,
                tradeAmount,
                TradeType.LONG,
                context.getAsset()
        );
    }

    @Transactional
    public void binanceOpenShortMarketOrder(
            EnrichedStrategyContext context,
            StrategyDecision decision,
            BigDecimal tradeAmount,
            String asset
    ) {
        tradeOpenService.openMarketOrder(
                context,
                decision,
                tradeAmount,
                TradeType.SHORT,
                asset
        );
    }

    @Transactional
    public void binanceCloseLongPositionsMarketOrder(
            Account account,
            List<TradePosition> tradePositions,
            String asset
    ) {
        if (account == null || tradePositions == null || tradePositions.isEmpty()) {
            return;
        }

        tradeCloseService.closeGroupedPositions(account, tradePositions, asset, TradeType.LONG);
    }

    @Transactional
    public void binanceCloseShortPositionsMarketOrder(
            Account account,
            List<TradePosition> tradePositions,
            String asset
    ) {
        if (account == null || tradePositions == null || tradePositions.isEmpty()) {
            return;
        }

        tradeCloseService.closeGroupedPositions(account, tradePositions, asset, TradeType.SHORT);
    }

    @Transactional
    public void binanceCloseLongPositionMarketOrder(
            Account account,
            TradePosition tradePosition,
            String asset
    ) {
        if (account == null || tradePosition == null) {
            return;
        }

        tradeCloseService.closeSinglePosition(account, tradePosition, asset, TradeType.LONG);
    }

    @Transactional
    public void binanceCloseShortPositionMarketOrder(
            Account account,
            TradePosition tradePosition,
            String asset
    ) {
        if (account == null || tradePosition == null) {
            return;
        }

        tradeCloseService.closeSinglePosition(account, tradePosition, asset, TradeType.SHORT);
    }

    @Transactional
    public void updateOpenTradePositions(
            Trades activeTrade,
            StrategyDecision decision
    ) {
        if (activeTrade == null || decision == null) {
            return;
        }

        tradeCloseService.updateOpenTradePositions(activeTrade, decision);
    }
}