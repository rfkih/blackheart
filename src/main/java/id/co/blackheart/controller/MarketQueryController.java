package id.co.blackheart.controller;

import id.co.blackheart.dto.response.LatestPriceResponse;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.marketquery.MarketQueryService;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class MarketQueryController {

    private final MarketQueryService marketQueryService;

    @GetMapping("/latest-price/{symbol}")
    public ResponseEntity<ResponseDto> getLatestPrice(@PathVariable String symbol) {
        LatestPriceResponse data = marketQueryService.getLatestPrice(symbol.toUpperCase());

        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(data)
                .build());
    }
}
