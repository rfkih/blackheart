package id.co.blackheart.service.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.service.portfolio.PortfolioService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class TelegramBotPollingService {

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.bot.query.key}")
    private String queryKey;

    private final RestTemplate restTemplate;
    private final IpMonitorService ipMonitorService;
    private final PortfolioService portfolioService;
    private final ObjectMapper objectMapper;

    private final AtomicLong updateOffset = new AtomicLong(0);

    public TelegramBotPollingService(RestTemplate restTemplate,
                                     IpMonitorService ipMonitorService,
                                     PortfolioService portfolioService,
                                     ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.ipMonitorService = ipMonitorService;
        this.portfolioService = portfolioService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${telegram.bot.poll.interval-ms:5000}")
    public void pollUpdates() {
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
