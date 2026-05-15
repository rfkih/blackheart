package id.co.blackheart.controller;

import id.co.blackheart.dto.request.MonteCarloRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.montecarlo.MonteCarloService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/montecarlo")
@RequiredArgsConstructor
@Profile("research")
@Tag(name = "MonteCarloController", description = "Controller for Monte Carlo Simulation")
public class MonteCarloController {

    private final MonteCarloService monteCarloService;
    private final JwtService jwtService;

    @PostMapping("/run")
    public ResponseEntity<ResponseDto> run(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody MonteCarloRequest request) {
        UUID userId = jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(monteCarloService.run(userId, request))
                .build());
    }
}
