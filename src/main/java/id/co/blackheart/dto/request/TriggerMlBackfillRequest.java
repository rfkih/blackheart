package id.co.blackheart.dto.request;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * POST body for {@code /api/v1/ml-ingest/backfill}. Triggers a historical
 * backfill job for one source. The service resolves {@code source} to the
 * matching {@code BACKFILL_ML_*} JobType, then routes through the existing
 * {@code HistoricalBackfillJobService}.
 *
 * <p>Params shape (handler-validated):
 * <pre>
 * {
 *   "start": "2024-12-01T00:00:00",
 *   "end":   "2026-05-14T00:00:00",
 *   "series_ids": [...]   // source-specific
 * }
 * </pre>
 */
public record TriggerMlBackfillRequest(
        String source,
        String symbol,
        JsonNode params
) {
}
