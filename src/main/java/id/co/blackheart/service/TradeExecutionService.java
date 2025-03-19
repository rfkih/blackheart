package id.co.blackheart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.client.DeepLearningClientService;
import id.co.blackheart.client.TokocryptoClientService;
import id.co.blackheart.dto.request.MarketOrderRequest;
import id.co.blackheart.dto.request.OrderDetailRequest;
import id.co.blackheart.dto.response.MarketOrderResponse;
import id.co.blackheart.dto.response.OrderDetailResponse;
import id.co.blackheart.dto.response.TokocryptoResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;



@Service
@Slf4j
@AllArgsConstructor
public class TradeExecutionService {

    TokocryptoClientService tokocryptoClientService;
    DeepLearningClientService deepLearningClientService;

    public MarketOrderResponse placeMarketOrder(MarketOrderRequest marketOrder) {

        try {
            TokocryptoResponse response = tokocryptoClientService.placeMarketOrder(marketOrder);

            if (response == null || response.getData() == null) {
                log.warn("⚠No data received for Asset: {}", marketOrder.getSymbol());
            }

            ObjectMapper objectMapper = new ObjectMapper();
            MarketOrderResponse marketOrderResponse = objectMapper.treeToValue(response.getData(), MarketOrderResponse.class);

            return marketOrderResponse;
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public OrderDetailResponse getOrderDetail(OrderDetailRequest orderDetailRequest) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            TokocryptoResponse response = tokocryptoClientService.orderDetail(orderDetailRequest);

            log.info("orderDetail : " + response);

            if (response == null || response.getData() == null) {
                log.warn("⚠No data received for ID Detail : {}", orderDetailRequest.getOrderId());
                throw new Exception("No data Received for ID Detail :" + orderDetailRequest.getOrderId());
            }

            return objectMapper.treeToValue(response.getData(), OrderDetailResponse.class);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}
