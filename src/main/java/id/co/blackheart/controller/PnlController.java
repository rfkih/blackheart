package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.pnl.PnlService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pnl")
@RequiredArgsConstructor
public class PnlController {

    private final PnlService pnlService;
    private final JwtService jwtService;

    @GetMapping("/summary")
    public ResponseEntity<ResponseDto> getSummary(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(defaultValue = "today") String period) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(pnlService.getSummary(userId, period))
                .build());
    }

    @GetMapping("/daily")
    public ResponseEntity<ResponseDto> getDaily(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String strategyCode) {
        UUID userId = extractUserId(authHeader);
        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(pnlService.getDaily(userId, fromDate, toDate, strategyCode))
                .build());
    }

    @GetMapping("/by-strategy")
    public ResponseEntity<ResponseDto> getByStrategy(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        UUID userId = extractUserId(authHeader);
        LocalDate fromDate = (from != null && !from.isBlank()) ? LocalDate.parse(from) : null;
        LocalDate toDate = (to != null && !to.isBlank()) ? LocalDate.parse(to) : null;
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(pnlService.getByStrategy(userId, fromDate, toDate))
                .build());
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(authHeader.substring(7));
    }
}
