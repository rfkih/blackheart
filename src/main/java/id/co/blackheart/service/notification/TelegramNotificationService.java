package id.co.blackheart.service.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class TelegramNotificationService {

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.chat.id}")
    private String chatId;

    private final RestTemplate restTemplate;

    public TelegramNotificationService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void sendMessage(String message) {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage"
                + "?chat_id=" + chatId
                + "&text=" + encodeMessage(message)
                + "&parse_mode=HTML";
        try {
            restTemplate.getForObject(url, String.class);
            log.info("[Telegram] Message sent: {}", message);
        } catch (Exception e) {
            log.error("[Telegram] Failed to send message", e);
        }
    }

    private String encodeMessage(String message) {
        return message.replace(" ", "%20")
                .replace("\n", "%0A")
                .replace("#", "%23");
    }
}