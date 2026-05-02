package id.co.blackheart.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Response shape for {@code /api/v1/strategy-params/...} — one saved preset.
 *
 * <p>Mirrors the V29 schema. Soft-deleted presets are returned only by the
 * by-id endpoint (used by historical backtest reruns); list endpoints filter
 * them out upstream.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StrategyParamResponse {

    private UUID paramId;
    private UUID accountStrategyId;

    /** User-supplied label, e.g. "aggressive RR=2". */
    private String name;

    /** Raw override map. */
    private Map<String, Object> overrides;

    private boolean active;
    private boolean deleted;

    private LocalDateTime deletedAt;

    /** Originating backtest run id, when the preset was saved from a run's
     *  Re-run-with-params flow. Null otherwise. */
    private UUID sourceBacktestRunId;

    private Long version;

    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
