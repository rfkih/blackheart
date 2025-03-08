package id.co.blackheart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.client.TokocryptoClientService;
import id.co.blackheart.dto.MarketOrder;
import id.co.blackheart.dto.MarketOrderResponse;
import id.co.blackheart.dto.TokocryptoResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;



@Service
@Slf4j
@AllArgsConstructor
public class TradeExecutionService {

    TokocryptoClientService tokocryptoClientService;

    public MarketOrderResponse placeMarketOrder(MarketOrder marketOrder) {

        try {
            TokocryptoResponse response = tokocryptoClientService.placeMarketOrder(marketOrder);

            if (response == null || response.getData() == null) {
                log.warn("⚠No data received for Asset: {}", marketOrder.getSymbol());
            }

            ObjectMapper objectMapper = new ObjectMapper();
            MarketOrderResponse marketOrderResponse = objectMapper.treeToValue(response.getData(), MarketOrderResponse.class);

            log.info(" marketOrder : " + marketOrderResponse);

            return marketOrderResponse;
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
