package id.co.blackheart.service.notification;

import id.co.blackheart.model.ServerIpLog;
import id.co.blackheart.repository.ServerIpLogRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class IpMonitorService {

    private static final String IP_CHECK_URL = "https://api.ipify.org?format=text";

    private final RestTemplate restTemplate;
    private final TelegramNotificationService telegramNotificationService;
    private final ServerIpLogRepository serverIpLogRepository;

    private final AtomicReference<String> lastKnownIp = new AtomicReference<>(null);

    @PostConstruct
    public void init() {
        serverIpLogRepository.findTopByOrderByRecordedAtDesc()
                .ifPresent(log -> lastKnownIp.set(log.getIpAddress()));
    }

    public String getCurrentPublicIp() {
        return restTemplate.getForObject(IP_CHECK_URL, String.class);
    }

    public void checkAndNotifyIfChanged() {
        try {
            String currentIp = getCurrentPublicIp();
            if (!StringUtils.hasText(currentIp)) {
                log.warn("[IpMonitor] Could not retrieve public IP.");
                return;
            }
            currentIp = currentIp.trim();

            String previous = lastKnownIp.get();

            if (previous == null) {
                log.info("[IpMonitor] Initial IP recorded: {}", currentIp);
                persistLog(currentIp, null, "INIT");
                lastKnownIp.set(currentIp);
                telegramNotificationService.sendMessage(
                        "\uD83D\uDD14 <b>Blackheart Server IP Initialized</b>\n"
                        + "IP: <code>" + currentIp + "</code>");

            } else if (!previous.equals(currentIp)) {
                log.warn("[IpMonitor] IP changed! {} → {}", previous, currentIp);
                persistLog(currentIp, previous, "CHANGED");
                lastKnownIp.set(currentIp);
                telegramNotificationService.sendMessage(
                        "\u26A0\uFE0F <b>Blackheart Server IP Changed!</b>\n"
                        + "Old IP: <code>" + previous + "</code>\n"
                        + "New IP: <code>" + currentIp + "</code>");

            } else {
                log.debug("[IpMonitor] IP unchanged: {}", currentIp);
            }
        } catch (Exception e) {
            log.error("[IpMonitor] Error checking IP", e);
        }
    }

    private void persistLog(String ip, String previousIp, String event) {
        ServerIpLog entry = new ServerIpLog();
        entry.setIpAddress(ip);
        entry.setPreviousIp(previousIp);
        entry.setEvent(event);
        entry.setRecordedAt(LocalDateTime.now());
        serverIpLogRepository.save(entry);
    }
}