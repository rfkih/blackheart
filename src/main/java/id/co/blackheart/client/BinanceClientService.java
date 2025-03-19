package id.co.blackheart.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.BinanceAssetRequest;
import id.co.blackheart.dto.request.BinanceOrderDetailRequest;
import id.co.blackheart.dto.request.BinanceOrderRequest;
import id.co.blackheart.dto.response.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;

@Service
@Slf4j

public class BinanceClientService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${binance.api.base-url}")
    private String baseUrl;

    public BinanceClientService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public BinanceAssetResponse getBinanceAssetDetails(BinanceAssetRequest binanceAssetRequest) {
        ResponseEntity<String> response = postRequest(
                baseUrl + "/get-asset-binance",
                binanceAssetRequest,
                buildJsonHeaders()
        );
        List<BinanceAssetDto> assets = decodeResponse(response, new TypeReference<List<BinanceAssetDto>>() {});
        BinanceAssetResponse result = new BinanceAssetResponse();
        result.setAssets(assets);
        return result;
    }

    public BinanceOrderDetailResponse orderDetailBinance(BinanceOrderDetailRequest orderDetailRequest) {
        ResponseEntity<String> response = postRequest(
                baseUrl + "/order-detail-binance",
                orderDetailRequest,
                buildBinanceHeaders(orderDetailRequest.getApiKey())
        );
        return decodeResponse(response, new TypeReference<BinanceOrderDetailResponse>() {});
    }

    public BinanceOrderResponse binanceMarketOrder(BinanceOrderRequest binanceOrderRequest) {
        ResponseEntity<String> response = postRequest(
                baseUrl + "/place-market-order-binance",
                binanceOrderRequest,
                buildJsonHeaders()
        );
        return decodeResponse(response, new TypeReference<BinanceOrderResponse>() {});
    }

    private HttpHeaders buildJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders buildBinanceHeaders(String apiKey) {
        HttpHeaders headers = buildJsonHeaders();
        headers.set("X-MBX-APIKEY", apiKey);
        return headers;
    }

    private ResponseEntity<String> postRequest(String url, Object body, HttpHeaders headers) {
        try {
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            return restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        } catch (HttpStatusCodeException ex) {
            log.error("API Error: {}", ex.getResponseBodyAsString());
            throw ex;
        }
    }

    private <T> T decodeResponse(ResponseEntity<String> response, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(response.getBody(), typeRef);
        } catch (IOException e) {
            log.error("Error decoding response: {}", e.getMessage());
            throw new RuntimeException("Failed to decode response", e);
        }
    }
}
