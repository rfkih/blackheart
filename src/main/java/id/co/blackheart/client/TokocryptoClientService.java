package id.co.blackheart.client;


import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.TokocryptoResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;


@Service
@Slf4j
public class TokocryptoClientService {

    private final RestTemplate restTemplate;
    private static final String BASE_URL = "http://localhost:3000/get-asset";
    private static final String API_KEY = "your_api_key_here";
    private final ObjectMapper objectMapper = new ObjectMapper();


    public TokocryptoClientService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public TokocryptoResponse getAssetDetails(String asset, int recvWindow, String apiKey, String apiSecret) {
        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL)
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

        if (response.getBody() == null || response.getBody().isEmpty()) {
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
}


