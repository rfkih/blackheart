package id.co.blackheart.service.trade;

import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.cache.CacheService;
import id.co.blackheart.service.redis.RedisPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static id.co.blackheart.util.TradeConstant.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeStateSyncService {

    private final TradesRepository tradesRepository;
    private final TradePositionRepository tradePositionRepository;
    private final CacheService cacheService;
    private final RedisPublisher redisPublisher;

    public void syncTradeState(UUID tradeId) {
        try {
            Trades trade = tradesRepository.findById(tradeId)
                    .orElseThrow(() -> new IllegalStateException("Trade not found after persistence"));

            if (STATUS_CLOSED.equalsIgnoreCase(trade.getStatus())) {
                cacheService.removeClosedTrade(trade.getUserId(), tradeId);
                redisPublisher.publishTradeStateChange(tradeId, STATUS_CLOSED);
                return;
            }

            List<TradePosition> openPositions =
                    tradePositionRepository.findAllByTradeIdAndStatus(tradeId, STATUS_OPEN);

            cacheService.cacheUserActiveTrade(
                    trade.getUserId(),
                    trade.getTradeId(),
                    trade,
                    openPositions
            );

            redisPublisher.publishTradeStateChange(tradeId, trade.getStatus());

        } catch (Exception e) {
            log.error("Failed to sync trade state | tradeId={}", tradeId, e);
        }
    }
}