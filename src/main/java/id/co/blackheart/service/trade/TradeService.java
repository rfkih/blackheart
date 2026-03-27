package id.co.blackheart.service.trade;

import id.co.blackheart.dto.strategy.StrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import static id.co.blackheart.util.TradeConstant.*;

@Service
@RequiredArgsConstructor
public class TradeService {

    private final TradeOpenService tradeOpenService;
    private final TradeCloseService tradeCloseService;

    @Transactional
    public void binanceOpenLongMarketOrder(
            StrategyContext context,
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
            StrategyContext context,
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
            Users user,
            List<TradePosition> tradePositions,
            String asset
    ) {
        tradeCloseService.closeGroupedPositions(user, tradePositions, asset, TradeType.LONG);
    }

    @Transactional
    public void binanceCloseShortPositionsMarketOrder(
            Users user,
            List<TradePosition> tradePositions,
            String asset
    ) {
        tradeCloseService.closeGroupedPositions(user, tradePositions, asset, TradeType.SHORT);
    }

    @Transactional
    public void binanceCloseLongPositionMarketOrder(
            Users user,
            TradePosition tradePosition,
            String asset
    ) {
        tradeCloseService.closeSinglePosition(user, tradePosition, asset, TradeType.LONG);
    }

    @Transactional
    public void binanceCloseShortPositionMarketOrder(
            Users user,
            TradePosition tradePosition,
            String asset
    ) {
        tradeCloseService.closeSinglePosition(user, tradePosition, asset, TradeType.SHORT);
    }

    @Transactional
    public void updateOpenTradePositions(
            Trades activeTrade,
            StrategyDecision decision
    ) {
        tradeCloseService.updateOpenTradePositions(activeTrade, decision);
    }
}