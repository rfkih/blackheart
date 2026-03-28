package id.co.blackheart.controller;


import id.co.blackheart.service.websocket.LivePnlSubscriptionRegistry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class LivePnlWebSocketController {

    private final LivePnlSubscriptionRegistry subscriptionRegistry;

    @MessageMapping("/pnl.subscribe")
    public void subscribe(SubscribePnlRequest request) {
        if (request != null && request.getAccountId() != null) {
            subscriptionRegistry.addAccount(request.getAccountId());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubscribePnlRequest {
        private UUID accountId;
    }
}