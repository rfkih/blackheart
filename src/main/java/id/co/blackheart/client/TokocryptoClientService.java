package id.co.blackheart.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.MarketOrderRequest;
import id.co.blackheart.dto.request.OrderDetailRequest;
import id.co.blackheart.dto.response.TokocryptoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class TokocryptoClientService {

    @Value("${nodejs.api.base-url}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public TokocryptoResponse getAssetDetails(String asset, int recvWindow, String apiKey, String apiSecret) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/get-asset")
                .queryParam("asset", asset)
                .queryParam("recvWindow", recvWindow)
                .queryParam("apiKey", apiKey)
                .queryParam("apiSecret", apiSecret)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(MediaType.parseMediaTypes("application/json"));
        headers.set("Accept-Encoding", "gzip, deflate, br");
        headers.set("Connection", "keep-alive");

        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

        if (response.getBody() == null || response.getBody().isEmpty()) {
            throw new IllegalArgumentException("Response body is null or empty");
        }

        return decodeResponse(response);
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

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        String url = baseUrl + "/api/place-market-order";

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);

        if (response.getBody() == null || response.getBody().isEmpty()) {
            throw new IllegalArgumentException("Response body is null or empty");
        }

        return decodeResponse(response);
    }

    public TokocryptoResponse orderDetail(OrderDetailRequest orderDetailRequest) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-MBX-APIKEY", orderDetailRequest.getApiKey());

            HttpEntity<OrderDetailRequest> requestEntity = new HttpEntity<>(orderDetailRequest, headers);
            String url = baseUrl + "/api/order-detail";

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);

            return decodeResponse(response);
        } catch (Exception e) {
            throw new RuntimeException("❌ Error fetching order details: " + e.getMessage(), e);
        }
    }

    private TokocryptoResponse decodeResponse(ResponseEntity<String> response) {
        try {
            TokocryptoResponse responseService = objectMapper.readValue(response.getBody(), TokocryptoResponse.class);

            if (responseService == null || responseService.getCode() != 0) {
                throw new IllegalStateException("Invalid response or code: " + response.getBody());
            }

            return responseService;
        } catch (IOException e) {
            log.warn("Error decoding response: {}", e.getMessage());
            throw new RuntimeException("Failed to decode response", e);
        }
    }
}
