package id.co.blackheart.controller;


import id.co.blackheart.dto.PredictionRequest;
import id.co.blackheart.dto.PredictionResponse;
import id.co.blackheart.dto.ResponseDto;
import id.co.blackheart.service.DeepLearningService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "v1/deep-learning")
@Slf4j
@Tag(name = "DeepLearningController", description = "Controller for Deep Learning Execution")
public class DeepLearningController {
    @Autowired
    DeepLearningService deepLearningService;

    @PostMapping("/predict")
    public ResponseEntity<ResponseDto> predict(@RequestBody PredictionRequest predictionRequest) {
        PredictionResponse response = deepLearningService.getPrediction(predictionRequest);
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }
}
