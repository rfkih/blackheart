package id.co.blackheart.service;


import id.co.blackheart.client.DeepLearningClientService;
import id.co.blackheart.dto.request.PredictionRequest;
import id.co.blackheart.dto.response.PredictionResponse;
import id.co.blackheart.model.TrainingJob;
import id.co.blackheart.repository.TrainingJobRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@AllArgsConstructor
public class DeepLearningService {
    DeepLearningClientService deepLearningClientService;
    TrainingJobRepository trainingJobRepository;


    public PredictionResponse getPrediction(PredictionRequest predictionRequest) {
        try {
            PredictionResponse response = deepLearningClientService.sendPredictionRequest();

            log.info("prediction Response : " + response);

            if (response == null) {
                log.warn("⚠No data received for ID Detail : {}", predictionRequest.getSymbol());
                throw new Exception("No data Received for ID Detail :" + predictionRequest.getSymbol());
            }

            return response;
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void executeTraining() {
        try {
            List<TrainingJob> trainingJobList = trainingJobRepository.findByModelAndStatus("BTCUSDT", "0");
            if (!trainingJobList.isEmpty()){
                log.info("Training Still Running !");
                return;
            }

            deepLearningClientService.sendTrainRequestAsync();
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
