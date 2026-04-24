package id.co.blackheart.service.websocket;

import id.co.blackheart.dto.response.ActiveTradePnlResponse;
import id.co.blackheart.service.tradequery.TradePnlQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes live P&L frames to STOMP subscribers on a fixed cadence.
 *
 * <p>Two guardrails keep this cheap as connection count grows:
 * <ul>
 *   <li><b>Overlap skip</b> — if the previous cycle's fan-out hasn't completed
 *       by the next tick, we skip rather than queue a second wave. Prevents
 *       executor backpressure on a slow DB / Redis blip from compounding.</li>
 *   <li><b>Frame dedupe</b> — if an account's frame is byte-identical to the
 *       last one sent (no price/qty/PnL movement), we suppress the SEND. Idle
 *       open positions stop chewing through the broker's outbound queue.</li>
 * </ul>
 */
@Slf4j
@Service
public class LivePnlPublisherService {

    private final SimpMessagingTemplate messagingTemplate;
    private final LivePnlSubscriptionRegistry subscriptionRegistry;
    private final TradePnlQueryService tradePnlQueryService;
    private final Executor taskExecutor;

    /**
     * Set true while a tick's fan-out is in flight. Used purely as a "skip
     * this cycle" gate — it's not a hard lock, so if one account's publish
     * takes longer than the tick interval the loop simply waits.
     */
    private final AtomicBoolean cycleInFlight = new AtomicBoolean(false);

    /**
     * Last frame published per account. Dedupe compares the full
     * {@code ActiveTradePnlResponse} via {@code equals} — Lombok's
     * {@code @Data}/{@code @Value} on the DTO gives us value equality for free.
     * Unbounded is fine in practice: keys are the live subscription set, not
     * unique connections, so size = concurrent active accounts.
     */
    private final Map<UUID, ActiveTradePnlResponse> lastFrame = new ConcurrentHashMap<>();

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

    @Scheduled(fixedDelay = 1000)
    public void publishLivePnl() {
        Set<UUID> accounts = subscriptionRegistry.getSubscribedAccountIds();
        if (accounts.isEmpty()) return;

        // Drop stale entries for accounts that are no longer subscribed so
        // lastFrame doesn't grow past the current working set.
        lastFrame.keySet().retainAll(accounts);

        if (!cycleInFlight.compareAndSet(false, true)) {
            log.debug("Live P&L cycle already in flight; skipping tick");
            return;
        }

        // Countdown of pending fan-out tasks — the last one to finish flips
        // the in-flight flag. Using a plain int[] as a cheap mutable counter.
        final int[] remaining = { accounts.size() };
        for (UUID accountId : accounts) {
            taskExecutor.execute(() -> {
                try {
                    publishOne(accountId);
                } finally {
                    synchronized (remaining) {
                        if (--remaining[0] <= 0) {
                            cycleInFlight.set(false);
                        }
                    }
                }
            });
        }
    }

    private void publishOne(UUID accountId) {
        try {
            ActiveTradePnlResponse response = tradePnlQueryService.getCurrentActiveTradePnl(accountId);
            ActiveTradePnlResponse previous = lastFrame.get(accountId);
            if (previous != null && previous.equals(response)) {
                // Nothing moved since the last publish; skip the SEND.
                return;
            }
            lastFrame.put(accountId, response);
            messagingTemplate.convertAndSend("/topic/pnl/" + accountId, response);
        } catch (Exception e) {
            log.error("Failed to publish live pnl for accountId={}", accountId, e);
        }
    }
}
