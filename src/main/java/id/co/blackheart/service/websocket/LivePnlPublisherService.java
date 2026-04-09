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

    @Scheduled(fixedDelay = 1000)
    public void publishLivePnl() {
        for (UUID accountId : subscriptionRegistry.getSubscribedAcccountId()) {
            try {
                log.debug("Publishing live PnL | accountId={}", accountId);
                ActiveTradePnlResponse response = tradePnlQueryService.getCurrentActiveTradePnl(accountId);
                messagingTemplate.convertAndSend("/topic/pnl/" + accountId, response);
            } catch (Exception e) {
                log.error("Failed to publish live pnl for accountId={}", accountId, e);
            }
        }
    }
}