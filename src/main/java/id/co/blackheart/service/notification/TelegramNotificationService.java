package id.co.blackheart.service.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TelegramNotificationService {

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.chat.ids}")
    private String chatIds;

    private final RestTemplate restTemplate;

    public TelegramNotificationService(@Qualifier("telegramRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void sendMessage(String message) {
        // No-op when the bot isn't configured (token blank) so the app stays
        // usable locally without a Telegram secret. Same guard as the polling
        // service — keeps us from calling /bot//sendMessage with an empty token.
        if (botToken == null || botToken.isBlank()) return;
        if (chatIds == null || chatIds.isBlank()) return;

        List<String> recipients = Arrays.stream(chatIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .toList();
        if (recipients.isEmpty()) return;

        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (String chatId : recipients) {
            try {
                Map<String, String> body = new HashMap<>();
                body.put("chat_id", chatId);
                body.put("text", message);
                body.put("parse_mode", "HTML");

                HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
                restTemplate.postForObject(url, request, String.class);
                log.info("[Telegram] Message sent to {}", chatId);
            } catch (Exception e) {
                log.error("[Telegram] Failed to send message to {}", chatId, e);
            }
        }
    }
}
