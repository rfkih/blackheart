package id.co.blackheart.service.websocket;

import id.co.blackheart.dto.response.ActiveTradePnlResponse;
import id.co.blackheart.service.tradequery.TradePnlQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LivePnlPublisherService {

    private final SimpMessagingTemplate messagingTemplate;
    private final LivePnlSubscriptionRegistry subscriptionRegistry;
    private final TradePnlQueryService tradePnlQueryService;

    @Scheduled(fixedRate = 1000)
    public void publishLivePnl() {
        for (UUID userId : subscriptionRegistry.getSubscribedUserIds()) {
            try {
                ActiveTradePnlResponse response = tradePnlQueryService.getCurrentActiveTradePnl(userId);
                messagingTemplate.convertAndSend("/topic/pnl/" + userId, response);
            } catch (Exception e) {
                log.error("Failed to publish live pnl for userId={}", userId, e);
            }
        }
    }
}