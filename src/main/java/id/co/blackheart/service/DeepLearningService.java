package id.co.blackheart.service;


import id.co.blackheart.client.DeepLearningClientService;
import id.co.blackheart.dto.PredictionRequest;
import id.co.blackheart.dto.PredictionResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@AllArgsConstructor
public class DeepLearningService {
    DeepLearningClientService deepLearningClientService;


    public PredictionResponse getPrediction(PredictionRequest predictionRequest) {
        try {
            PredictionResponse response = deepLearningClientService.sendPredictionRequest();

            log.info("prediction Response : " + response);

            if (response == null) {
                log.warn("⚠No data received for ID Detail : {}", predictionRequest.getPair());
                throw new Exception("No data Received for ID Detail :" + predictionRequest.getPair());
            }

            return response;
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
