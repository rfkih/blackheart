package id.co.blackheart.service.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Cleans up the live-PnL subscription registry when a STOMP session ends.
 * Without this, the registry grew monotonically — the publisher kept running
 * a DB query per second per stale account id for the lifetime of the process.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LivePnlSessionLifecycleListener {

    private final LivePnlSubscriptionRegistry subscriptionRegistry;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null) return;
        subscriptionRegistry.removeSession(sessionId);
        log.debug("Pruned pnl subscriptions for disconnected session={}", sessionId);
    }
}
