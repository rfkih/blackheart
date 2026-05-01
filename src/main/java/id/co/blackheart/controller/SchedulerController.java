package id.co.blackheart.controller;

import id.co.blackheart.dto.request.SchedulerRequest;
import id.co.blackheart.dto.request.SchedulerUpdateRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.dto.response.SchedulerJobStatusResponse;
import id.co.blackheart.model.SchedulerJob;
import id.co.blackheart.service.scheduler.SchedulerService;
import id.co.blackheart.util.ResponseCode;

import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
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
@Profile("!research")
@RequiredArgsConstructor
@Tag(name = "SchedulerController", description = "Controller for Scheduler")
@PreAuthorize("hasRole('ADMIN')")
public class SchedulerController {

    private final SchedulerService schedulerService;

    /**
     * List every scheduler job with its persisted config, in-process schedule
     * state, and the next computed fire time. Powers the /research dashboard
     * Scheduler panel. Admin-only via class-level {@code @PreAuthorize}.
     */
    @GetMapping
    public ResponseEntity<ResponseDto> listSchedulers() {
        List<SchedulerJobStatusResponse> jobs = schedulerService.listJobsWithStatus();
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(jobs)
                .build());
    }

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

    /**
     * Partial update: cron expression and/or status. Cron is validated
     * server-side via CronTrigger before persisting. On success the
     * in-process schedule is reconciled (started, stopped, or restarted
     * with the new cron) so the change takes effect immediately without
     * a JVM restart.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<ResponseDto> updateScheduler(@PathVariable Long id,
                                                       @RequestBody SchedulerUpdateRequest request) {
        SchedulerJobStatusResponse updated = schedulerService.updateJob(
                id, request.getCronExpression(), request.getStatus());
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(updated)
                .build());
    }

    /**
     * Operator-triggered one-shot run. Routes through the same switch as
     * the cron path; runs on the TaskScheduler pool so the HTTP response
     * doesn't block on a long-running job (TRAIN_MODEL etc).
     */
    @PostMapping("/{id}/run")
    public ResponseEntity<ResponseDto> runScheduler(@PathVariable Long id) {
        schedulerService.runJobOnce(id);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data("Scheduler job " + id + " queued for immediate execution.")
                .build());
    }
}
