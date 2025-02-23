package id.co.blackheart.config;


import id.co.blackheart.stream.BinanceWebSocketClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class WebSocketRunner {

    @Bean
    public ApplicationRunner startWebSocket(BinanceWebSocketClient webSocketClient) {
        return args -> {
            log.info("Starting WebSocket connection...");
            webSocketClient.connect();
        };
    }
}
