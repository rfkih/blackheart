package id.co.blackheart.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Request body for unified {@code /api/v1/strategy-params/...} PUT and PATCH endpoints.
 *
 * <p>Generic shape: a free-form override map. Keys and value types are validated
 * server-side against the archetype's parameter schema (resolved via the bound
 * {@code account_strategy → strategy_definition.archetype}).
 *
 * <p>Validation responsibilities:
 * <ul>
 *   <li>Controller verifies the map is non-null (handled by {@code @NotNull}).</li>
 *   <li>Service layer (or its caller) verifies each key is allowed by the
 *       archetype schema and each value matches the declared type/range.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
public class StrategyParamUpdateRequest {

    @NotNull(message = "overrides map is required (use empty {} to clear)")
    private Map<String, Object> overrides = new HashMap<>();
}
