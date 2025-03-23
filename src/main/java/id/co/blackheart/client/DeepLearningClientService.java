package id.co.blackheart.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.response.PredictionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeepLearningClientService {

    @Value("${fastapi.base-url}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PredictionResponse sendPredictionRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(MediaType.parseMediaTypes("application/json"));

        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);

        String predictUrl = baseUrl + "/predict";

        ResponseEntity<String> response = restTemplate.exchange(
                predictUrl,
                HttpMethod.POST,
                requestEntity,
                String.class
        );

        return decodeResponse(response);
    }

    @Async // Optional: Enable @EnableAsync in your SpringBootApp class!
    public CompletableFuture<Void> sendTrainRequestAsync() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(MediaType.parseMediaTypes("application/json"));

        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);
        String trainUrl = baseUrl + "/train";

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    trainUrl,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );
            log.info("[TRAIN_API] Response: {}", response.getStatusCode());
        } catch (Exception e) {
            log.warn("[TRAIN_API] Request failed: {}", e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    private PredictionResponse decodeResponse(ResponseEntity<String> response) {
        try {
            PredictionResponse responseService = objectMapper.readValue(response.getBody(), PredictionResponse.class);

            if (responseService == null) {
                throw new IllegalStateException("Invalid response or code: " + response.getBody());
            }
            return responseService;
        } catch (IOException e) {
            log.warn("Error decoding response: {}", e.getMessage());
            throw new RuntimeException("Failed to decode asset details", e);
        }
    }
}
