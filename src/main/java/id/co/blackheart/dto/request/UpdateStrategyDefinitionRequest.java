package id.co.blackheart.dto.request;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Partial update for {@code PATCH /api/v1/strategy-definitions/:id} — admin-only.
 *
 * <p>Every field is optional; only non-null values are applied. The canonical
 * {@code strategyCode} is deliberately NOT mutable here: downstream rows
 * (account_strategy, backtest_run) reference it by string, so renaming it
 * in-place would orphan data silently. Deprecate + create instead.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateStrategyDefinitionRequest {

    @Size(max = 200, message = "Strategy name is too long")
    private String strategyName;

    @Size(max = 100, message = "Strategy type is too long")
    private String strategyType;

    @Size(max = 4000, message = "Description is too long")
    private String description;

    @Size(max = 20, message = "Status is too long")
    private String status;

    @Size(max = 64, message = "Archetype is too long")
    private String archetype;

    @Positive(message = "archetypeVersion must be positive")
    private Integer archetypeVersion;

    /**
     * When provided, replaces the existing {@code spec_jsonb} wholesale.
     * Pass an empty map to clear. Null = leave unchanged.
     */
    private Map<String, Object> specJsonb;

    @Positive(message = "specSchemaVersion must be positive")
    private Integer specSchemaVersion;

    /** Optional free-form rationale recorded in {@code strategy_definition_history.change_reason}. */
    @Size(max = 1000, message = "Change reason is too long")
    private String changeReason;

    /** V40 — definition-scope kill-switch. Null = leave unchanged. */
    private Boolean enabled;

    /** V40 — definition-scope paper flag. Null = leave unchanged. */
    private Boolean simulated;
}
