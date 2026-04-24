package id.co.blackheart.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    /**
     * Comma-separated list of origins allowed to open a WS connection. Mirrors
     * the REST CORS config. Defaults to the dev frontend; override with
     * {@code WS_ALLOWED_ORIGINS=https://app.example.com} in staging/prod.
     */
    @Value("${app.ws.allowed-origins:http://localhost:3000}")
    private String[] allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // setAllowedOrigins is stricter than setAllowedOriginPatterns("*") —
        // the previous config let any Origin connect, which combined with the
        // unauthenticated CONNECT frame leaked PnL cross-tenant.
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
        // user-destination prefix lets controllers send to a single session
        // via convertAndSendToUser(principal, "/queue/…", payload) if needed.
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }
}
