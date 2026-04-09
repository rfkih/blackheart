package id.co.blackheart.controller;

import id.co.blackheart.dto.request.PredictionRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.DeepLearningService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("v1/deep-learning")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "DeepLearningController", description = "Controller for Deep Learning Execution")
public class DeepLearningController {

    private final DeepLearningService deepLearningService;

    @PostMapping("/predict")
    public ResponseEntity<ResponseDto> predict(@RequestBody PredictionRequest predictionRequest) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(deepLearningService.getPrediction(predictionRequest))
                .build());
    }
}
