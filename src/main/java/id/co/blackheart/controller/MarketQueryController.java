package id.co.blackheart.controller;

import id.co.blackheart.dto.response.LatestPriceResponse;
import id.co.blackheart.dto.response.MarketDataResponse;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.marketquery.MarketQueryService;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class MarketQueryController {

    private final MarketQueryService marketQueryService;
    private final MarketDataRepository marketDataRepository;

    @GetMapping("/latest-price/{symbol}")
    public ResponseEntity<ResponseDto> getLatestPrice(@PathVariable String symbol) {
        LatestPriceResponse data = marketQueryService.getLatestPrice(symbol.toUpperCase());
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(data)
                .build());
    }

    @GetMapping
    public ResponseEntity<ResponseDto> getCandles(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "200") int limit) {

        List<MarketData> candles;
        if (from != null && to != null) {
            LocalDateTime fromDt = parseDateTime(from);
            LocalDateTime toDt = parseDateTime(to);
            candles = marketDataRepository.findBySymbolIntervalAndRange(symbol.toUpperCase(), interval, fromDt, toDt);
        } else {
            candles = marketDataRepository.findLatestCandles(symbol.toUpperCase(), interval, limit);
        }

        List<MarketDataResponse> result = candles.stream()
                .map(m -> MarketDataResponse.builder()
                        .symbol(m.getSymbol())
                        .interval(m.getInterval())
                        .openTime(toEpochMs(m.getStartTime()))
                        .open(m.getOpenPrice())
                        .high(m.getHighPrice())
                        .low(m.getLowPrice())
                        .close(m.getClosePrice())
                        .volume(m.getVolume())
                        .closeTime(toEpochMs(m.getEndTime()))
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(result)
                .build());
    }

    private LocalDateTime parseDateTime(String s) {
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            return LocalDate.parse(s).atStartOfDay();
        }
    }

    private Long toEpochMs(LocalDateTime ldt) {
        if (ldt == null) return null;
        return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
