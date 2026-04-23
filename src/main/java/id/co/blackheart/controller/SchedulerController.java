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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Scheduler admin endpoints. Controls global signal-engine lifecycle, so only
 * platform ADMINs should start/stop — a per-user stop would halt trading for
 * every other tenant.
 */
@RestController
@RequestMapping("/api/v1/scheduler")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "SchedulerController", description = "Controller for Scheduler")
@PreAuthorize("hasRole('ADMIN')")
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
