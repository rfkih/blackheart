package id.co.blackheart.config;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.service.user.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.util.StringUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates every STOMP CONNECT frame with the same JWT that gates the
 * REST API, AND authorizes every SUBSCRIBE frame against the session's
 * principal. Without SUBSCRIBE-level auth the CONNECT gate was a half-fix —
 * any authenticated session could still subscribe to
 * {@code /topic/pnl/<other-account>} and receive broadcasts for accounts it
 * does not own.
 *
 * <p>Authorization rules:
 * <ul>
 *   <li><b>CONNECT</b> — requires a valid JWT in the {@code Authorization}
 *       native STOMP header. Stamps the session with a {@link StompPrincipal}.</li>
 *   <li><b>SUBSCRIBE /topic/pnl/{accountId}</b> — {@code accountId} must be
 *       owned by the principal's userId; otherwise the frame is rejected with
 *       {@link AccessDeniedException}.</li>
 *   <li><b>SUBSCRIBE /user/**</b> — allowed; Spring's user-destination routing
 *       already routes these privately per session.</li>
 *   <li><b>SUBSCRIBE anything else</b> — allowed only for authenticated sessions
 *       (the SUBSCRIBE frame is rejected if no principal is attached).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String PNL_TOPIC_PREFIX = "/topic/pnl/";
    private static final String BACKTEST_TOPIC_PREFIX = "/topic/backtest/";

    private final JwtService jwtService;
    private final AccountRepository accountRepository;
    private final BacktestRunRepository backtestRunRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        StompCommand command = accessor.getCommand();
        if (command == null) return message;

        switch (command) {
            case CONNECT -> handleConnect(accessor);
            case SUBSCRIBE -> handleSubscribe(accessor);
            default -> { /* SEND, UNSUBSCRIBE, DISCONNECT etc. — require session but no topic gate */ }
        }
        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String token = extractToken(accessor);
        if (token == null) {
            throw new IllegalArgumentException("STOMP CONNECT rejected — missing Authorization header");
        }
        try {
            String email = jwtService.extractEmail(token);
            if (email == null || !jwtService.isTokenValid(token, email)) {
                throw new IllegalArgumentException("STOMP CONNECT rejected — invalid token");
            }
            UUID userId = jwtService.extractUserId(token);
            String role = jwtService.extractRole(token);
            StompPrincipal principal = new StompPrincipal(email, userId, role);
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
            accessor.setUser(principal);
            accessor.setLeaveMutable(true);
            // Stash the Authentication for any downstream @MessageMapping that
            // prefers a Spring Authentication over the raw Principal.
            accessor.setHeader("authentication", auth);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("STOMP CONNECT token validation failed: {}", e.getMessage());
            throw new IllegalArgumentException("STOMP CONNECT rejected — invalid token");
        }
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        StompPrincipal principal = (accessor.getUser() instanceof StompPrincipal p) ? p : null;
        if (principal == null) {
            log.warn("STOMP SUBSCRIBE rejected — no authenticated principal on session");
            throw new AccessDeniedException("STOMP SUBSCRIBE rejected — unauthenticated");
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }

        if (destination.startsWith(PNL_TOPIC_PREFIX)) {
            String tail = destination.substring(PNL_TOPIC_PREFIX.length());
            if (!StringUtils.hasText(tail)) {
                throw new AccessDeniedException("STOMP SUBSCRIBE rejected — missing accountId");
            }
            UUID accountId;
            try {
                accountId = UUID.fromString(tail);
            } catch (IllegalArgumentException e) {
                throw new AccessDeniedException("STOMP SUBSCRIBE rejected — invalid accountId");
            }

            Optional<Account> account = accountRepository.findByAccountId(accountId);
            if (account.isEmpty()
                    || account.get().getUserId() == null
                    || !account.get().getUserId().equals(principal.getUserId())) {
                // Log with the attempting user's id — useful for detection of enumeration.
                log.warn(
                        "STOMP SUBSCRIBE rejected — account not owned by caller | destination={} callerUserId={}",
                        destination, principal.getUserId()
                );
                throw new AccessDeniedException("Not authorized for this topic");
            }
            return;
        }

        if (destination.startsWith(BACKTEST_TOPIC_PREFIX)) {
            String tail = destination.substring(BACKTEST_TOPIC_PREFIX.length());
            if (!StringUtils.hasText(tail)) {
                throw new AccessDeniedException("STOMP SUBSCRIBE rejected — missing backtestRunId");
            }
            UUID runId;
            try {
                runId = UUID.fromString(tail);
            } catch (IllegalArgumentException e) {
                throw new AccessDeniedException("STOMP SUBSCRIBE rejected — invalid backtestRunId");
            }
            Optional<BacktestRun> run = backtestRunRepository.findByIdAndUserId(runId, principal.getUserId());
            if (run.isEmpty()) {
                log.warn(
                        "STOMP SUBSCRIBE rejected — backtest run not owned by caller | destination={} callerUserId={}",
                        destination, principal.getUserId()
                );
                throw new AccessDeniedException("Not authorized for this topic");
            }
            return;
        }
        // /user/** destinations are routed privately by Spring per-session; no gate here.
        // Anything else (public market-data topics etc.) is allowed for authenticated sessions.
    }

    private String extractToken(StompHeaderAccessor accessor) {
        // Browsers attach the header via stompClient.connect(headers, ...) —
        // the JS client sends it as a native STOMP header on CONNECT.
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null) return null;
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }
}
