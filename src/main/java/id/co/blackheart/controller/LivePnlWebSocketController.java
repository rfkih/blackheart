package id.co.blackheart.controller;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import id.co.blackheart.config.StompPrincipal;
import id.co.blackheart.model.Account;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.service.websocket.LivePnlSubscriptionRegistry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class LivePnlWebSocketController {

    private final LivePnlSubscriptionRegistry subscriptionRegistry;
    private final AccountRepository accountRepository;

    /**
     * Register the caller's session to receive live PnL frames for a given
     * account. Previously this accepted any accountId with no auth — combined
     * with the unauthenticated WS endpoint, any peer could subscribe to any
     * account. Now we require a signed STOMP CONNECT (enforced by the
     * {@code WebSocketAuthInterceptor}) and verify the target account is
     * actually owned by the authenticated user.
     */
    @MessageMapping("/pnl.subscribe")
    public void subscribe(
            SubscribePnlRequest request,
            Principal principal,
            @Header("simpSessionId") String sessionId
    ) {
        if (request == null || request.getAccountId() == null) {
            return;
        }
        if (!(principal instanceof StompPrincipal stompPrincipal)) {
            log.warn("pnl.subscribe rejected — no StompPrincipal on session");
            return;
        }

        UUID requestedAccountId = request.getAccountId();
        Account account = accountRepository.findByAccountId(requestedAccountId).orElse(null);
        if (account == null) {
            log.warn("pnl.subscribe rejected — account not found | accountId={}", requestedAccountId);
            return;
        }
        if (account.getUserId() == null || !account.getUserId().equals(stompPrincipal.getUserId())) {
            log.warn(
                    "pnl.subscribe rejected — account not owned by caller | accountId={} userId={}",
                    requestedAccountId,
                    stompPrincipal.getUserId()
            );
            return;
        }

        subscriptionRegistry.addAccount(sessionId, requestedAccountId);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubscribePnlRequest {
        private UUID accountId;
    }
}
