package id.co.blackheart.controller;


import id.co.blackheart.dto.*;
import id.co.blackheart.service.TradeExecutionService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
    public ResponseEntity<ResponseDto> placeMarketOrder(@RequestBody MarketOrderRequest marketOrder) {
        MarketOrderResponse response = tradeExecutionService.placeMarketOrder(marketOrder);
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }

    @PostMapping("/order-detail")
    public ResponseEntity<ResponseDto> orderDetail(@RequestBody OrderDetailRequest orderDetailRequest) {
        OrderDetailResponse response = tradeExecutionService.getOrderDetail(orderDetailRequest);
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }
}
