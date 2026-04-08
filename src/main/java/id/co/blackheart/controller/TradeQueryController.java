package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.tradequery.TradeQueryService;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trades")
@RequiredArgsConstructor
public class TradeQueryController {

    private final TradeQueryService tradeQueryService;

    @GetMapping("/account/{accountId}/active")
    public ResponseEntity<ResponseDto> getActiveTradesByAccountId(@PathVariable UUID accountId) {

        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(tradeQueryService.getActiveTradesByAccountId(accountId))
                .build());
    }
}