package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ActiveTradePnlResponse;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.tradequery.TradePnlQueryService;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trades")
@RequiredArgsConstructor
public class TradePnlQueryController {

    private final TradePnlQueryService tradePnlQueryService;

    @GetMapping("/users/{userId}/active-pnl")
    public ResponseEntity<ResponseDto> getCurrentActiveTradePnl(@PathVariable UUID userId) {
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(tradePnlQueryService.getCurrentActiveTradePnl(userId))
                .build());
    }
}