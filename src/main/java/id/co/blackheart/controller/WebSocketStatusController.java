package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.stream.BinanceWebSocketClient;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Live Binance WebSocket heartbeat for the admin /research dashboard.
 *
 * <p>Lives in its own controller (not {@code ServerInfoController}) because
 * {@code BinanceWebSocketClient} is excluded from {@code researchBootJar}
 * — injecting it into a shared controller would fail classloading on the
 * research JVM. {@code @Profile("!research")} ensures this controller only
 * registers on the trading JVM, which is the JVM that actually owns the
 * Binance feed.
 */
@RestController
@RequestMapping("/api/v1/server")
@RequiredArgsConstructor
@Profile("!research")
public class WebSocketStatusController {

    /** Beyond this age the dashboard tile flips red — 30s matches the WS watchdog timeout. */
    private static final Duration STALE_THRESHOLD = Duration.ofSeconds(30);

    private final BinanceWebSocketClient binanceWebSocketClient;

    @GetMapping("/ws-status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResponseDto> getWsStatus() {
        Instant lastMessageAt = binanceWebSocketClient.getLastMessageTime();
        long ageMs = Duration.between(lastMessageAt, Instant.now()).toMillis();

        Map<String, Object> payload = new HashMap<>();
        payload.put("running", binanceWebSocketClient.isRunning());
        payload.put("symbol", binanceWebSocketClient.getSymbol());
        payload.put("intervals", binanceWebSocketClient.getSubscribedIntervals());
        payload.put("lastMessageAt", lastMessageAt.toString());
        payload.put("ageMs", ageMs);
        payload.put("stale", ageMs > STALE_THRESHOLD.toMillis());

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(payload)
                .build());
    }
}
