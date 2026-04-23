package id.co.blackheart.service.websocket;

import id.co.blackheart.dto.response.ActiveTradePnlResponse;
import id.co.blackheart.service.tradequery.TradePnlQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class LivePnlPublisherService {

    private final SimpMessagingTemplate messagingTemplate;
    private final LivePnlSubscriptionRegistry subscriptionRegistry;
    private final TradePnlQueryService tradePnlQueryService;
    private final Executor taskExecutor;

    public LivePnlPublisherService(
            SimpMessagingTemplate messagingTemplate,
            LivePnlSubscriptionRegistry subscriptionRegistry,
            TradePnlQueryService tradePnlQueryService,
            @Qualifier("taskExecutor") Executor taskExecutor
    ) {
        this.messagingTemplate = messagingTemplate;
        this.subscriptionRegistry = subscriptionRegistry;
        this.tradePnlQueryService = tradePnlQueryService;
        this.taskExecutor = taskExecutor;
    }

    /**
     * Publishes one PnL frame per subscribed account. Each account's fetch +
     * send is submitted to the shared executor so a slow DB/upstream call for
     * one account can't stall updates for every other account. The scheduler
     * itself returns immediately — next tick fires on the next fixed-delay
     * regardless of how long the fan-out takes.
     */
    @Scheduled(fixedDelay = 1000)
    public void publishLivePnl() {
        Set<UUID> accounts = subscriptionRegistry.getSubscribedAccountIds();
        if (accounts.isEmpty()) return;
        for (UUID accountId : accounts) {
            taskExecutor.execute(() -> publishOne(accountId));
        }
    }

    private void publishOne(UUID accountId) {
        try {
            ActiveTradePnlResponse response = tradePnlQueryService.getCurrentActiveTradePnl(accountId);
            messagingTemplate.convertAndSend("/topic/pnl/" + accountId, response);
        } catch (Exception e) {
            log.error("Failed to publish live pnl for accountId={}", accountId, e);
        }
    }
}
