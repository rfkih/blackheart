package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.repository.ServerIpLogRepository;
import id.co.blackheart.service.notification.IpMonitorService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/server")
@RequiredArgsConstructor
@Tag(name = "ServerInfoController", description = "Controller for server diagnostics")
public class ServerInfoController {

    private final IpMonitorService ipMonitorService;
    private final ServerIpLogRepository serverIpLogRepository;

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
                .data(serverIpLogRepository.findAllByOrderByRecordedAtDesc())
                .build());
    }
}
