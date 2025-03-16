package id.co.blackheart.client;


import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.PredictionResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

@Slf4j
@Service
@AllArgsConstructor
public class DeepLearningClientService {
    private static final String PREDICT_URL = "http://127.0.0.1:8000/predict";
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PredictionResponse sendPredictionRequest() {
        // Set headers
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");

        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                PREDICT_URL,
                HttpMethod.POST,
                requestEntity,
                String.class
        );

        return decodeResponse(response);
    }

    private PredictionResponse decodeResponse(ResponseEntity<String> response) {
        try {
            PredictionResponse responseService = objectMapper.readValue(response.getBody(), PredictionResponse.class);

            if (responseService == null) {
                throw new IllegalStateException("Invalid response or code: " + response.getBody());
            }
            return responseService;
        } catch (IOException e) {
            log.info("Error decoding response: " + e.getMessage());
            throw new RuntimeException("Failed to decode asset details", e);
        }
    }

}
