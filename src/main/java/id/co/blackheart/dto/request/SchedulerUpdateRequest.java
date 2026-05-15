package id.co.blackheart.dto.request;

import lombok.Getter;
import lombok.Setter;

/**
 * Partial-update payload for {@code PATCH /api/v1/scheduler/{id}}. Both
 * fields are optional; supplying neither is a 400 (no-op). The controller
 * validates the cron expression server-side before persisting so a bad
 * cron cannot leave the in-process scheduler in an inconsistent state.
 */
@Getter
@Setter
public class SchedulerUpdateRequest {
    /** Spring 6-field cron (sec min hr day mon dow). Null = leave unchanged. */
    private String cronExpression;
    /** "1" = enabled (rescheduled), "0" = disabled (stopped). Null = leave unchanged. */
    private String status;
}
