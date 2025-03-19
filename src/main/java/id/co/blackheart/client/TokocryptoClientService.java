package id.co.blackheart.client;


import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.MarketOrderRequest;
import id.co.blackheart.dto.request.OrderDetailRequest;
import id.co.blackheart.dto.response.TokocryptoResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


@Service
@Slf4j
@AllArgsConstructor
public class TokocryptoClientService {

    private final RestTemplate restTemplate;
    private static final String BASE_URL_GET_ASSET = "http://localhost:3000/api/get-asset";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String BASE_URL_ORDER = "http://localhost:3000/api/place-market-order";
    private static final String BASE_URL_ORDER_DETAIL = "http://localhost:3000/api/order-detail";



    public TokocryptoResponse getAssetDetails(String asset, int recvWindow, String apiKey, String apiSecret) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL_GET_ASSET)
                .queryParam("asset", asset)
                .queryParam("recvWindow", recvWindow)
                .queryParam("apiKey", apiKey)
                .queryParam("apiSecret", apiSecret)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("Accept-Encoding", "gzip, deflate, br");
        headers.set("Connection", "keep-alive");

        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

        if (response.getBody() == null || response.toString().isEmpty()) {
            throw new IllegalArgumentException("Response body is null or empty");
        }

        return decodeResponse(response);
    }

    private TokocryptoResponse decodeResponse(ResponseEntity<String> response) {
        try {
            TokocryptoResponse responseService = objectMapper.readValue(response.getBody(), TokocryptoResponse.class);

            if (responseService == null || responseService.getCode() != 0) {
                throw new IllegalStateException("Invalid response or code: " + response.getBody());
            }

            return responseService;
        } catch (IOException e) {
            log.info("Error decoding response: " + e.getMessage());
            throw new RuntimeException("Failed to decode asset details", e);
        }
    }


    public TokocryptoResponse placeMarketOrder(MarketOrderRequest marketOrder) {

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
        ResponseEntity<String> response = restTemplate.exchange(BASE_URL_ORDER, HttpMethod.POST, request, String.class);

        if (response.getBody() == null || response.getBody().isEmpty()) {
            throw new IllegalArgumentException("Response body is null or empty");
        }

        return decodeResponse(response);

    }

    /**
     * Sends a POST request to Tokocrypto API with the request body.
     */
    public TokocryptoResponse orderDetail(OrderDetailRequest orderDetailRequest) {
        try {
            // Set headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-MBX-APIKEY", orderDetailRequest.getApiKey());

            // Create HTTP request with body
            HttpEntity<OrderDetailRequest> requestEntity = new HttpEntity<>(orderDetailRequest, headers);

            // Send HTTP POST request
            ResponseEntity<String> response = restTemplate.exchange(BASE_URL_ORDER_DETAIL, HttpMethod.POST, requestEntity, String.class);

            // Deserialize response
            return decodeResponse(response);
        } catch (Exception e) {
            throw new RuntimeException("❌ Error fetching order details: " + e.getMessage(), e);
        }
    }
}


