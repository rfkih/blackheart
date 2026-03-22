package id.co.blackheart.service.live;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.tradelistener.ListenerContext;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.repository.UsersRepository;
import id.co.blackheart.service.tradelistener.TradeListenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradeListenerService {

    private final TradePositionRepository tradePositionRepository;
    private final UsersRepository usersRepository;
    private final LivePositionSnapshotMapper livePositionSnapshotMapper;
    private final TradeListenerService tradeListenerService;
    private final LiveTradingDecisionExecutorService liveTradingDecisionExecutorService;

    public void process(String asset, BigDecimal latestPrice) {

        List<TradePosition> activeTradePositions =
                tradePositionRepository.findAllByAssetAndStatus(asset, "OPEN");

        if (activeTradePositions.isEmpty()) {
            return;
        }

        for (TradePosition activeTradePosition : activeTradePositions) {
            try {
                PositionSnapshot positionSnapshot = livePositionSnapshotMapper.toSnapshot(activeTradePosition);

                ListenerContext listenerContext = ListenerContext.builder()
                        .asset(asset)
                        .interval(activeTradePosition.getInterval())
                        .positionSnapshot(positionSnapshot)
                        .latestPrice(latestPrice)
                        .build();

                ListenerDecision listenerDecision = tradeListenerService.evaluate(listenerContext);

                if (!listenerDecision.isTriggered()) {
                    continue;
                }

                Users user = usersRepository.findByUserId(activeTradePosition.getUserId());

                if (user == null) {
                    log.warn("Listener close skipped because user not found | tradePositionId={} userId={}",
                            activeTradePosition.getTradePositionId(), activeTradePosition.getUserId());
                    continue;
                }

                liveTradingDecisionExecutorService.executeListenerClosePosition(
                        user,
                        activeTradePosition,
                        asset,
                        listenerDecision
                );

            } catch (Exception e) {
                log.error("Live listener failed | tradePositionId={} asset={}",
                        activeTradePosition.getTradePositionId(), asset, e);
            }
        }
    }
}