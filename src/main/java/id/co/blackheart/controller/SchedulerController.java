package id.co.blackheart.controller;


import id.co.blackheart.dto.request.SchedulerRequest;
import id.co.blackheart.service.SchedulerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "v1/scheduler")
@Slf4j
@Tag(name = "SchedulerController", description = "Controller for Scheduler")
public class SchedulerController {

    SchedulerService schedulerService;

    public SchedulerController(SchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    @PostMapping("/start")
    public String startScheduler(@RequestBody SchedulerRequest request) {
//        schedulerService.startScheduler(request);
        return "Scheduler " + request.getSchedulerId() + " started.";
    }

    @PostMapping("/stop/{schedulerId}")
    public String stopScheduler(@PathVariable String schedulerId) {
        schedulerService.stopScheduler(schedulerId);
        return "Scheduler " + schedulerId + " stopped.";
    }
}
