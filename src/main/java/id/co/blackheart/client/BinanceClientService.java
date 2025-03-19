package id.co.blackheart.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.BinanceAssetRequest;
import id.co.blackheart.dto.response.BinanceAssetDto;
import id.co.blackheart.dto.response.BinanceAssetResponse;
import id.co.blackheart.dto.response.TokocryptoResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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

        return decodeResponse(response);
    }



    private BinanceAssetResponse decodeResponse(ResponseEntity<String> response) {
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

}
