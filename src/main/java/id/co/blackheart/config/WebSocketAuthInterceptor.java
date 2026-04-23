package id.co.blackheart.config;

import id.co.blackheart.service.user.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Authenticates every STOMP CONNECT frame with the same JWT that gates the
 * REST API. Without this interceptor the previous setup left {@code /ws}
 * wide open — any network peer could subscribe to {@code /topic/pnl/*} for
 * any account and read live positions across tenants.
 *
 * <p>Validation on SUBSCRIBE / SEND frames relies on Spring putting the
 * session's {@link java.security.Principal} on every subsequent message so
 * controllers can authorise against it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = extractToken(accessor);
            if (token == null) {
                throw new IllegalArgumentException("STOMP CONNECT rejected — missing Authorization header");
            }
            String email;
            try {
                email = jwtService.extractEmail(token);
                if (email == null || !jwtService.isTokenValid(token, email)) {
                    throw new IllegalArgumentException("STOMP CONNECT rejected — invalid token");
                }
            } catch (Exception e) {
                log.warn("STOMP CONNECT token validation failed: {}", e.getMessage());
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
        }
        return message;
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
