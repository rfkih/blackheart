package id.co.blackheart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.client.PredictionClientService;
import id.co.blackheart.client.TokocryptoClientService;
import id.co.blackheart.dto.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;



@Service
@Slf4j
@AllArgsConstructor
public class TradeExecutionService {

    TokocryptoClientService tokocryptoClientService;
    PredictionClientService predictionClientService;

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

    public PredictionResponse getPrediction(PredictionRequest predictionRequest) {
        try {
            PredictionResponse response = predictionClientService.sendPredictionRequest();

            log.info("prediction Response : " + response);

            if (response == null) {
                log.warn("⚠No data received for ID Detail : {}", predictionRequest.getPair());
                throw new Exception("No data Received for ID Detail :" + predictionRequest.getPair());
            }

            return response;
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
