package id.co.blackheart.controller;

import id.co.blackheart.dto.request.SchedulerRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.SchedulerJob;
import id.co.blackheart.service.scheduler.SchedulerService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/scheduler")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "SchedulerController", description = "Controller for Scheduler")
public class SchedulerController {

    private final SchedulerService schedulerService;

    @PostMapping("/start")
    public ResponseEntity<ResponseDto> startScheduler(@RequestBody SchedulerRequest request) {
        SchedulerJob schedulerJob = new SchedulerJob();
        schedulerJob.setJobName(request.getJobName());
        schedulerService.startScheduler(schedulerJob);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data("Scheduler " + request.getSchedulerId() + " started.")
                .build());
    }

    @PostMapping("/stop/{schedulerId}")
    public ResponseEntity<ResponseDto> stopScheduler(@PathVariable String schedulerId) {
        schedulerService.stopScheduler(schedulerId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data("Scheduler " + schedulerId + " stopped.")
                .build());
    }
}
