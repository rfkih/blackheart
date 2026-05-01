package id.co.blackheart.service.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.service.portfolio.PortfolioService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@Profile("!research")
public class TelegramBotPollingService {

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.bot.query.key}")
    private String queryKey;

    @Value("${telegram.chat.ids}")
    private String chatIdsConfig;

    private Set<Long> allowedChatIds;

    private final RestTemplate restTemplate;
    private final IpMonitorService ipMonitorService;
    private final PortfolioService portfolioService;
    private final ObjectMapper objectMapper;

    private final AtomicLong updateOffset = new AtomicLong(0);

    public TelegramBotPollingService(@Qualifier("telegramRestTemplate") RestTemplate restTemplate,
                                     IpMonitorService ipMonitorService,
                                     PortfolioService portfolioService,
                                     ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.ipMonitorService = ipMonitorService;
        this.portfolioService = portfolioService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        allowedChatIds = chatIdsConfig == null
                ? Set.of()
                : Arrays.stream(chatIdsConfig.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .map(Long::parseLong)
                        .collect(Collectors.toSet());
        if (isBotDisabled()) {
            log.info("[TelegramBot] Disabled — TELEGRAM_BOT_TOKEN not set; polling will no-op");
        } else {
            log.info("[TelegramBot] Allowed chat IDs: {}", allowedChatIds);
        }
    }

    /**
     * The bot stays dormant when no token is configured. Without this guard we
     * hammered {@code https://api.telegram.org/bot/getUpdates} (literal empty
     * token) every few seconds and filled the log with 404s.
     */
    private boolean isBotDisabled() {
        return botToken == null || botToken.isBlank();
    }

    @Scheduled(fixedDelayString = "${telegram.bot.poll.interval-ms:5000}")
    public void pollUpdates() {
        if (isBotDisabled()) return;
        try {
            String url = "https://api.telegram.org/bot" + botToken
                    + "/getUpdates?offset=" + updateOffset.get() + "&limit=100&timeout=0";

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.path("ok").asBoolean()) {
                return;
            }

            JsonNode updates = root.path("result");
            for (JsonNode update : updates) {
                long updateId = update.path("update_id").asLong();
                updateOffset.set(updateId + 1);

                JsonNode message = update.path("message");
                if (message.isMissingNode()) {
                    continue;
                }

                String text = message.path("text").asText("").trim();
                long chatId = message.path("chat").path("id").asLong();

                if (!allowedChatIds.contains(chatId)) {
                    log.warn("[TelegramBot] Rejected message from unauthorized chat {}", chatId);
                    continue;
                }

                if (text.equalsIgnoreCase(queryKey)) {
                    log.info("[TelegramBot] IP query key received from chat {}", chatId);
                    handleIpQuery(chatId);
                }
            }
        } catch (Exception e) {
            log.error("[TelegramBot] Error polling updates", e);
        }
    }

    private void handleIpQuery(long chatId) {
        try {
            String ip = ipMonitorService.getCurrentPublicIp();

            StringBuilder sb = new StringBuilder();
            sb.append("\uD83D\uDDA5 <b>Blackheart Server IP</b>\n");
            sb.append("Current IP: <code>").append(ip).append("</code>\n\n");
            sb.append("<b>Binance Connectivity</b>\n");

            Map<String, String> connectivity = portfolioService.checkBinanceConnectivity();
            if (connectivity.isEmpty()) {
                sb.append("No active accounts found.");
            } else {
                for (Map.Entry<String, String> entry : connectivity.entrySet()) {
                    String status = entry.getValue();
                    String icon = "OK".equals(status) ? "\u2705" : "\u274C";
                    sb.append(icon).append(" <b>").append(entry.getKey()).append("</b>: ");
                    if ("OK".equals(status)) {
                        sb.append("IP registered");
                    } else {
                        sb.append("<i>").append(status).append("</i>");
                    }
                    sb.append("\n");
                }
            }

            sendReply(chatId, sb.toString());
        } catch (Exception e) {
            log.error("[TelegramBot] Failed to handle IP query for chat {}", chatId, e);
            sendReply(chatId, "Failed to retrieve server IP or Binance connectivity.");
        }
    }

    private void sendReply(long chatId, String text) {
        if (isBotDisabled()) return;
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "HTML");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            restTemplate.postForObject(url, request, String.class);
            log.info("[TelegramBot] Reply sent to chat {}", chatId);
        } catch (Exception e) {
            log.error("[TelegramBot] Failed to send reply to chat {}", chatId, e);
        }
    }
}
