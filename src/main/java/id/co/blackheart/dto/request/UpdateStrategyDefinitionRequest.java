package id.co.blackheart.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
