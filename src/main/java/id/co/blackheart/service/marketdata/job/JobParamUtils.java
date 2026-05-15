package id.co.blackheart.service.marketdata.job;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * Shared parsers for {@code HistoricalBackfillJob.params} JsonNode fields.
 * Every {@link HistoricalJobHandler} validates its own params shape, but
 * the primitives — pull a date, pull a non-blank string — are identical
 * across handlers. Extracted to one place so a malformed-input change
 * happens once.
 *
 * <p>All methods throw {@link IllegalArgumentException} on bad input —
 * the async runner translates that into a FAILED job with the message
 * exposed to the operator. Never return null for "missing required" —
 * use the {@code require…} variants when the field is mandatory.
 */
public final class JobParamUtils {

    private JobParamUtils() {
    }

    /**
     * Parse an optional ISO LocalDateTime field. Returns null when the
     * params blob is null, the key is absent, or its value is JSON null.
     * Throws when the value is present but not a parseable string.
     */
    public static LocalDateTime parseLocalDateTime(JsonNode params, String key) {
        if (params == null) return null;
        JsonNode node = params.get(key);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) {
            throw new IllegalArgumentException(
                    "params." + key + " must be an ISO LocalDateTime string (e.g. 2024-01-15T10:30:00)");
        }
        try {
            return LocalDateTime.parse(node.asText());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "params." + key + " is not a valid ISO LocalDateTime: " + node.asText());
        }
    }

    /**
     * Parse an optional string field with a default fallback. Throws if
     * the value is present but non-textual.
     */
    public static String getString(JsonNode params, String key, String defaultValue) {
        if (params == null) return defaultValue;
        JsonNode node = params.get(key);
        if (node == null || node.isNull()) return defaultValue;
        if (!node.isTextual()) {
            throw new IllegalArgumentException("params." + key + " must be a string");
        }
        return node.asText();
    }

    /**
     * Require a non-blank string field. Throws on missing, null, non-textual,
     * or blank values.
     */
    public static String requireString(JsonNode params, String key) {
        if (params == null) {
            throw new IllegalArgumentException("params is required (looking for " + key + ")");
        }
        JsonNode node = params.get(key);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("params." + key + " must be a non-blank string");
        }
        return node.asText().trim();
    }
}
