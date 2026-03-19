package id.co.blackheart.service.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.tradelistener.ListenerContext;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Trades;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.repository.UsersRepository;
import id.co.blackheart.service.tradelistener.TradeListenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradeListenerService {

    private final TradesRepository tradesRepository;
    private final UsersRepository usersRepository;
    private final LivePositionSnapshotMapper livePositionSnapshotMapper;
    private final TradeListenerService tradeListenerService;
    private final LiveTradingDecisionExecutorService liveTradingDecisionExecutorService;

    public void process(String asset, MarketData marketData) {

        List<Trades> activeTrades = tradesRepository.findAllOpenTradesByAsset(asset);

        if (activeTrades == null || activeTrades.isEmpty()) {
            return;
        }

        for (Trades activeTrade : activeTrades) {
            try {
                PositionSnapshot positionSnapshot = livePositionSnapshotMapper.toSnapshot(activeTrade);

                ListenerContext listenerContext = ListenerContext.builder()
                        .asset(asset)
                        .interval("15m")
                        .positionSnapshot(positionSnapshot)
                        .monitorCandle(marketData)
                        .build();

                ListenerDecision listenerDecision = tradeListenerService.evaluate(listenerContext);

                if (!listenerDecision.isTriggered()) {
                    continue;
                }

                Users user = usersRepository.findByUserId(activeTrade.getUserId());

                if (user == null) {
                    log.warn("Listener close skipped because user not found | tradeId={} userId={}",
                            activeTrade.getTradeId(), activeTrade.getUserId());
                    continue;
                }

                log.info("Listener triggered | tradeId={} userId={} asset={} side={} reason={} exitPrice={}",
                        activeTrade.getTradeId(),
                        activeTrade.getUserId(),
                        asset,
                        activeTrade.getSide(),
                        listenerDecision.getExitReason(),
                        listenerDecision.getExitPrice());

                liveTradingDecisionExecutorService.executeListenerClose(
                        user,
                        activeTrade,
                        asset,
                        listenerDecision
                );

            } catch (Exception e) {
                log.error("Live listener failed | tradeId={} asset={}",
                        activeTrade.getTradeId(), asset, e);
            }
        }
    }
}