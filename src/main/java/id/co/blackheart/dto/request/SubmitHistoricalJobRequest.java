package id.co.blackheart.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import id.co.blackheart.model.JobType;

/**
 * POST body for {@code /api/v1/historical/jobs}. The {@code params} node is
 * passed through opaquely to the registered {@code HistoricalJobHandler} —
 * each handler validates the shape it expects.
 */
public record SubmitHistoricalJobRequest(
        JobType jobType,
        String symbol,
        String interval,
        JsonNode params
) {
}
