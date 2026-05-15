package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResearchLaunchResponse;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.ops.ResearchLaunchService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator endpoints to drive the research-JVM lifecycle from the dashboard.
 * Lives only on the trading JVM (the supervisor); a research-side instance
 * would be unable to start itself once down. Admin-only because the research
 * JVM is shared infrastructure — restarting it interrupts every operator's
 * sweeps and backtests.
 */
@RestController
@RequestMapping("/api/v1/admin/research")
@Slf4j
@Profile("!research")
@RequiredArgsConstructor
@Tag(name = "ResearchLaunchController", description = "Lifecycle control for the research JVM")
@PreAuthorize("hasRole('ADMIN')")
public class ResearchLaunchController {

    private final ResearchLaunchService researchLaunchService;

    @PostMapping("/start")
    public ResponseEntity<ResponseDto> start() {
        ResearchLaunchResponse result = researchLaunchService.start();
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(result)
                .build());
    }

    @PostMapping("/stop")
    public ResponseEntity<ResponseDto> stop(HttpServletRequest request) {
        ResearchLaunchResponse result = researchLaunchService.stop(request.getHeader(HttpHeaders.COOKIE));
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(result)
                .build());
    }

    @PostMapping("/restart")
    public ResponseEntity<ResponseDto> restart(HttpServletRequest request) {
        ResearchLaunchResponse result = researchLaunchService.restart(request.getHeader(HttpHeaders.COOKIE));
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(result)
                .build());
    }
}
