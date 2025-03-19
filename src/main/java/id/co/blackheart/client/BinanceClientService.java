package id.co.blackheart.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.BinanceAssetRequest;
import id.co.blackheart.dto.request.BinanceOrderDetailRequest;
import id.co.blackheart.dto.request.OrderDetailRequest;
import id.co.blackheart.dto.response.BinanceAssetDto;
import id.co.blackheart.dto.response.BinanceAssetResponse;
import id.co.blackheart.dto.response.BinanceOrderDetailResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;

@Service
@Slf4j
@AllArgsConstructor
public class BinanceClientService {
    private final RestTemplate restTemplate;
    private static final String BASE_URL_GET_ASSET = "http://localhost:3000/api/get-asset-binance";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String BASE_URL_ORDER = "http://localhost:3000/api/place-market-order-binance";
    private static final String BASE_URL_ORDER_DETAIL = "http://localhost:3000/api/order-detail-binance";

    public BinanceAssetResponse getBinanceAssetDetails(BinanceAssetRequest binanceAssetRequest) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<BinanceAssetRequest> entity = new HttpEntity<>(binanceAssetRequest, headers);

        ResponseEntity<String> response = restTemplate.exchange(BASE_URL_GET_ASSET, HttpMethod.POST, entity, String.class);

        if (response.getBody() == null || response.getBody().isEmpty()) {
            throw new IllegalArgumentException("Response body is null or empty");
        }

        return decodeAssetResponse(response);
    }



    private BinanceAssetResponse decodeAssetResponse(ResponseEntity<String> response) {
        try {

            List<BinanceAssetDto> listAsset = objectMapper.readValue(
                    response.getBody(),
                    new TypeReference<List<BinanceAssetDto>>() {}
            );

            if (listAsset == null || listAsset.isEmpty()) {
                throw new IllegalStateException("Invalid response or empty array: " + response.getBody());
            }

            BinanceAssetResponse binanceAssetResponse = new BinanceAssetResponse();
            binanceAssetResponse.setAssets(listAsset);

            return binanceAssetResponse;
        } catch (IOException e) {
            log.info("Error decoding response: " + e.getMessage());
            throw new RuntimeException("Failed to decode asset details", e);
        }
    }


    /**
     * Sends a POST request to Binance API with the request body.
     */
    public BinanceOrderDetailResponse orderDetailBinance(BinanceOrderDetailRequest orderDetailRequest) {
        try {
            // Set headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-MBX-APIKEY", orderDetailRequest.getApiKey());

            // Create HTTP request with body
            HttpEntity<BinanceOrderDetailRequest> requestEntity = new HttpEntity<>(orderDetailRequest, headers);

            // Send HTTP POST request
            ResponseEntity<String> response = restTemplate.exchange(BASE_URL_ORDER_DETAIL, HttpMethod.POST, requestEntity, String.class);

            // Deserialize response
            return decodeOrderDetailResponse(response);
        } catch (Exception e) {
            throw new RuntimeException("❌ Error fetching order details: " + e.getMessage(), e);
        }
    }

    private BinanceOrderDetailResponse decodeOrderDetailResponse(ResponseEntity<String> response) {
        try {
            BinanceOrderDetailResponse orderDetailResponse = objectMapper.readValue(
                    response.getBody(),
                    new TypeReference<BinanceOrderDetailResponse>() {}
            );

            if (orderDetailResponse == null) {
                throw new IllegalStateException("Invalid response or empty array: " + response.getBody());
            }

            return orderDetailResponse;
        } catch (IOException e) {
            log.info("Error decoding response: " + e.getMessage());
            throw new RuntimeException("Failed to decode asset details", e);
        }
    }

}
