package id.co.blackheart.dto.request;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SchedulerRequest {
    String schedulerId;
    String jobType;
    String jobName;
    String mode;
    Long intervalMs;
    Integer hour;
    Integer minute;
}
