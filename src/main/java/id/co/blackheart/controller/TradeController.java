package id.co.blackheart.controller;


import id.co.blackheart.dto.MarketOrder;
import id.co.blackheart.service.TradeExecutionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "v1/trade")
@Slf4j
@Tag(name = "TradeController", description = "Controller for Trade Execution")
public class TradeController {

    @Autowired
    private TradeExecutionService tradeExecutionService;

    @PostMapping("/place-market-order")
    public ResponseEntity<String> placeMarketOrder(@RequestBody MarketOrder marketOrder) {
        String response = tradeExecutionService.placeMarketOrder(marketOrder);
        return ResponseEntity.ok(response);
    }
}
