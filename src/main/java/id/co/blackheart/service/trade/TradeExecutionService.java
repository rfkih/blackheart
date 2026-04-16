package id.co.blackheart.service.trade;

import id.co.blackheart.client.BinanceClientService;
import id.co.blackheart.dto.request.BinanceOrderDetailRequest;
import id.co.blackheart.dto.request.BinanceOrderRequest;
import id.co.blackheart.dto.response.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;



@Service
@Slf4j
@AllArgsConstructor
public class TradeExecutionService {

    private final BinanceClientService binanceClientService;


    public BinanceOrderResponse binanceMarketOrder(BinanceOrderRequest binanceOrderRequest) {

        try {
            BinanceOrderResponse response = binanceClientService.binanceMarketOrder(binanceOrderRequest);

            if (response == null) {
                log.warn("⚠No data received for Asset: {}", binanceOrderRequest.getSymbol());
            }

            return response;
        } catch (Exception e) {
            log.error("[binanceMarketOrder] Request failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }


    public BinanceOrderDetailResponse getOrderDetailBinance(BinanceOrderDetailRequest orderDetailRequest) {
        try {
            BinanceOrderDetailResponse response = binanceClientService.orderDetailBinance(orderDetailRequest);
            log.info("orderDetailBinance : {}", response);

            if (response == null) {
                log.warn("⚠No data received for ID Detail : {}", orderDetailRequest.getOrderId());
                throw new RuntimeException("No data received for ID Detail: " + orderDetailRequest.getOrderId());
            }

            return response;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}
