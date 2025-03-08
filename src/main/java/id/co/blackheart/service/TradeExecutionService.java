package id.co.blackheart.service;

import id.co.blackheart.dto.MarketOrder;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;


@Service
@Slf4j
@AllArgsConstructor
public class TradeExecutionService {


    private final String BASE_URL = "http://localhost:3000/api/place-market-order";

    public String placeMarketOrder(MarketOrder marketOrder) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("symbol", marketOrder.getSymbol());
            requestBody.put("side", marketOrder.getSide());
            requestBody.put("amount", marketOrder.getAmount());
            requestBody.put("isQuoteQty", marketOrder.isQuoteQty());
            requestBody.put("apiKey", marketOrder.getApiKey());
            requestBody.put("apiSecret", marketOrder.getApiSecret());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Send HTTP request
            RestTemplate restTemplate = new RestTemplate();
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(BASE_URL, HttpMethod.POST, request, String.class);

            return response.getBody();
        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Error placing market order: " + e.getMessage();
        }
    }
}
