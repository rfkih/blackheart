package id.co.blackheart.service.marketdata.job;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Param parsing is the same logic in 4 handlers — JobParamUtils consolidates
 * it. Tests focus on edge cases that would otherwise be caught only on the
 * worker thread (and surface as a FAILED job) — null params, missing keys,
 * non-textual values, malformed dates.
 */
class JobParamUtilsTest {

    private ObjectNode params() {
        return JsonNodeFactory.instance.objectNode();
    }

    // ── parseLocalDateTime ───────────────────────────────────────────────────

    @Test
    void parseLocalDateTime_validIso_returnsTimestamp() {
        ObjectNode p = params();
        p.put("from", "2024-01-15T10:30:00");
        assertEquals(
                LocalDateTime.of(2024, 1, 15, 10, 30, 0),
                JobParamUtils.parseLocalDateTime(p, "from")
        );
    }

    @Test
    void parseLocalDateTime_nullParams_returnsNull() {
        assertNull(JobParamUtils.parseLocalDateTime(null, "from"));
    }

    @Test
    void parseLocalDateTime_missingKey_returnsNull() {
        assertNull(JobParamUtils.parseLocalDateTime(params(), "from"));
    }

    @Test
    void parseLocalDateTime_jsonNull_returnsNull() {
        ObjectNode p = params();
        p.putNull("from");
        assertNull(JobParamUtils.parseLocalDateTime(p, "from"));
    }

    @Test
    void parseLocalDateTime_nonTextual_throws() {
        ObjectNode p = params();
        p.put("from", 42);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> JobParamUtils.parseLocalDateTime(p, "from")
        );
        assert ex.getMessage().contains("from");
        assert ex.getMessage().contains("ISO LocalDateTime");
    }

    @Test
    void parseLocalDateTime_malformedString_throws() {
        ObjectNode p = params();
        p.put("from", "not-a-date");
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> JobParamUtils.parseLocalDateTime(p, "from")
        );
        assert ex.getMessage().contains("not-a-date");
    }

    // ── getString ────────────────────────────────────────────────────────────

    @Test
    void getString_present_returnsValue() {
        ObjectNode p = params();
        p.put("mode", "warmup");
        assertEquals("warmup", JobParamUtils.getString(p, "mode", "DEFAULT"));
    }

    @Test
    void getString_missing_returnsDefault() {
        assertEquals("DEFAULT", JobParamUtils.getString(params(), "mode", "DEFAULT"));
    }

    @Test
    void getString_nullParams_returnsDefault() {
        assertEquals("DEFAULT", JobParamUtils.getString(null, "mode", "DEFAULT"));
    }

    @Test
    void getString_nonTextual_throws() {
        ObjectNode p = params();
        p.put("mode", true);
        assertThrows(
                IllegalArgumentException.class,
                () -> JobParamUtils.getString(p, "mode", "DEFAULT")
        );
    }

    // ── requireString ────────────────────────────────────────────────────────

    @Test
    void requireString_present_returnsTrimmed() {
        ObjectNode p = params();
        p.put("column", "  slope_200  ");
        assertEquals("slope_200", JobParamUtils.requireString(p, "column"));
    }

    @Test
    void requireString_missing_throws() {
        ObjectNode p = params();
        assertThrows(
                IllegalArgumentException.class,
                () -> JobParamUtils.requireString(p, "column")
        );
    }

    @Test
    void requireString_blank_throws() {
        ObjectNode p = params();
        p.put("column", "   ");
        assertThrows(
                IllegalArgumentException.class,
                () -> JobParamUtils.requireString(p, "column")
        );
    }

    @Test
    void requireString_nullParams_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JobParamUtils.requireString(null, "column")
        );
    }
}
