package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.ServerIpLog;
import id.co.blackheart.repository.ServerIpLogRepository;
import id.co.blackheart.service.notification.IpMonitorService;
import id.co.blackheart.service.notification.TelegramNotificationService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/server")
@RequiredArgsConstructor
@Tag(name = "ServerInfoController", description = "Controller for server diagnostics")
public class ServerInfoController {

    private final IpMonitorService ipMonitorService;
    private final ServerIpLogRepository serverIpLogRepository;
    private final TelegramNotificationService telegramNotificationService;

    @GetMapping("/ip")
    public ResponseEntity<ResponseDto> getServerIp() {
        String ip = ipMonitorService.getCurrentPublicIp();
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(ip)
                .build());
    }

    @GetMapping("/ip/history")
    public ResponseEntity<ResponseDto> getIpHistory() {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(serverIpLogRepository.findTop100ByOrderByRecordedAtDesc())
                .build());
    }

    /**
     * Latest IP-monitor snapshot. Drives the in-app whitelist warning banner —
     * when {@code event == "CHANGED"} the frontend nudges the user to update
     * their Binance API key whitelist before trades start failing. Reads from
     * the persisted log (kept up to date by the IP_MONITOR scheduler) rather
     * than calling ipify on every poll, so the endpoint is cheap and safe to
     * hit at frequent intervals.
     */
    @GetMapping("/ip/status")
    public ResponseEntity<ResponseDto> getIpStatus() {
        Map<String, Object> payload = new HashMap<>();
        serverIpLogRepository.findTopByOrderByRecordedAtDesc().ifPresent(latest -> {
            payload.put("currentIp", latest.getIpAddress());
            payload.put("previousIp", latest.getPreviousIp());
            payload.put("event", latest.getEvent());
            payload.put("recordedAt", latest.getRecordedAt());
        });
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(payload)
                .build());
    }

    @PostMapping("/telegram/send")
    public ResponseEntity<ResponseDto> sendTelegramMessage() {
        String ip = ipMonitorService.getCurrentPublicIp();
        telegramNotificationService.sendMessage(
                "\uD83D\uDDA5 <b>Blackheart Server IP</b>\n"
                + "Current IP: <code>" + ip + "</code>");
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data("Message sent. IP: " + ip)
                .build());
    }
}
