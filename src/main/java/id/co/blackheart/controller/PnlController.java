package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.pnl.PnlService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
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
        LocalDate fromDate = StringUtils.hasText(from) ? LocalDate.parse(from) : null;
        LocalDate toDate = StringUtils.hasText(to) ? LocalDate.parse(to) : null;
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(pnlService.getByStrategy(userId, fromDate, toDate))
                .build());
    }

    /**
     * Equity curve for one account over a millisecond window.
     * Each sample is {@code baseline + cumulativeRealizedPnl} at that moment,
     * with running-peak drawdown.
     *
     * Query params:
     * <ul>
     *   <li>{@code accountId} (UUID, required) — must belong to the caller.</li>
     *   <li>{@code from} (epoch ms, required) — window start.</li>
     *   <li>{@code to}   (epoch ms, required) — window end.</li>
     *   <li>{@code initialCapital} (BigDecimal, optional) — baseline equity in USDT;
     *       defaults to 10,000 when omitted or non-positive.</li>
     * </ul>
     */
    @GetMapping("/equity")
    public ResponseEntity<ResponseDto> getEquity(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam UUID accountId,
            @RequestParam long from,
            @RequestParam long to,
            @RequestParam(required = false) BigDecimal initialCapital) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(pnlService.getEquityCurve(userId, accountId, from, to, initialCapital))
                .build());
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
    }
}
