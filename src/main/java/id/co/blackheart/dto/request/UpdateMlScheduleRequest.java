package id.co.blackheart.dto.request;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * PATCH body for {@code /api/v1/ml-ingest/schedules/{id}}. Any subset of
 * fields may be set; null fields are left unchanged. Server validates the
 * cron expression via {@code CronExpression.parse()} when provided.
 */
public record UpdateMlScheduleRequest(
        String cronExpression,
        Integer lookbackHours,
        Boolean enabled,
        JsonNode config
) {
}
