package id.co.blackheart.controller;

import id.co.blackheart.dto.request.ResearchPauseRequest;
import id.co.blackheart.dto.response.ResearchControlResponse;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.research.ResearchControlService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Pause / resume the autonomous research loop. Lives on the trading JVM
 * (admin namespace, sibling of {@code ResearchLaunchController}) because the
 * research JVM may itself be down — and because the OS-cron orchestrator
 * (research-tick.sh) reads the flag directly from the DB, so neither JVM
 * needs to be up for the pause to take effect on the next tick.
 */
@RestController
@RequestMapping("/api/v1/admin/research/control")
@Slf4j
@Profile("!research")
@RequiredArgsConstructor
@Tag(name = "ResearchControlController", description = "Pause/resume autonomous research loop")
@PreAuthorize("hasRole('ADMIN')")
public class ResearchControlController {

    private final ResearchControlService researchControlService;
    private final JwtService jwtService;

    @GetMapping
    public ResponseEntity<ResponseDto> getStatus() {
        ResearchControlResponse status = researchControlService.getStatus();
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(status)
                .build());
    }

    @PostMapping("/pause")
    public ResponseEntity<ResponseDto> pause(
            @RequestBody(required = false) ResearchPauseRequest body,
            @RequestHeader("Authorization") String authHeader) {
        UUID actorUserId = extractUserId(authHeader);
        String reason = body == null ? null : body.getReason();
        ResearchControlResponse status = researchControlService.pause(reason, actorUserId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .responseDesc("Research loop paused")
                .data(status)
                .build());
    }

    @PostMapping("/resume")
    public ResponseEntity<ResponseDto> resume(@RequestHeader("Authorization") String authHeader) {
        UUID actorUserId = extractUserId(authHeader);
        ResearchControlResponse status = researchControlService.resume(actorUserId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .responseDesc("Research loop resumed")
                .data(status)
                .build());
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
    }
}
