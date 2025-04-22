package id.co.blackheart.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.PredictionRequest;
import id.co.blackheart.dto.request.TrainRequest;
import id.co.blackheart.dto.response.PredictionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeepLearningClientService {

    @Value("${fastapi.base-url}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    public PredictionResponse sendPredictionRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        PredictionRequest predictionRequest = new PredictionRequest();
        predictionRequest.setInterval("15m");
        predictionRequest.setSymbol("BTCUSDT");
        predictionRequest.setStock(false);

        HttpEntity<PredictionRequest> requestEntity = new HttpEntity<>(predictionRequest, headers);

        String predictUrl = baseUrl + "/predict";

        ResponseEntity<String> response = restTemplate.exchange(
                predictUrl,
                HttpMethod.POST,
                requestEntity,
                String.class
        );

        return decodeResponse(response);
    }

    public Mono<PredictionResponse> sendPredictionRequestReactive() {
        PredictionRequest request = new PredictionRequest();
        request.setInterval("15m");
        request.setSymbol("BTCUSDT");
        request.setStock(false);

        return webClientBuilder.build()
                .post()
                .uri(baseUrl + "/predict")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PredictionResponse.class)
                .doOnNext(response -> log.info("[PREDICT_API] Success"))
                .doOnError(err -> log.warn("[PREDICT_API] Failed: {}", err.getMessage()));
    }


    @Async("taskExecutor")
    public void sendTrainRequestAsync() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        String trainUrl = baseUrl + "/train";

        try {
            TrainRequest trainRequest = new TrainRequest();
            trainRequest.setSymbol("BTCUSDT");
            trainRequest.setInterval("15m");
            trainRequest.setThresholdDown(-4);
            trainRequest.setThresholdUp(4);

            HttpEntity<TrainRequest> requestEntity = new HttpEntity<>(trainRequest, headers);

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
        CompletableFuture.completedFuture(null);
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
