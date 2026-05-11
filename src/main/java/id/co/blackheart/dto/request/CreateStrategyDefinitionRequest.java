package id.co.blackheart.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Payload for {@code POST /api/v1/strategy-definitions} — admin-only.
 *
 * <p>Represents a row in the platform's strategy catalogue. {@code strategyCode}
 * is the stable identifier that the rest of the system (account_strategy,
 * backtest_run, param tables) keys on, so it must be unique and
 * change-resistant. The code pattern matches what existing strategy tables
 * already use.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateStrategyDefinitionRequest {

    @NotBlank(message = "Strategy code is required")
    @Size(min = 2, max = 100, message = "Strategy code must be between 2 and 100 characters")
    @Pattern(
            regexp = "^[A-Z0-9_]+$",
            message = "Strategy code must be UPPER_SNAKE_CASE (letters, digits, underscores)"
    )
    private String strategyCode;

    @NotBlank(message = "Strategy name is required")
    @Size(max = 200, message = "Strategy name is too long")
    private String strategyName;

    /**
     * High-level family — e.g. TREND, MEAN_REVERSION, BREAKOUT, MOMENTUM.
     * Kept as a string (not an enum) so the admin can add new types without
     * a migration.
     */
    @NotBlank(message = "Strategy type is required")
    @Size(max = 100, message = "Strategy type is too long")
    private String strategyType;

    /** Markdown is fine — the UI renders it as plaintext for now. */
    @Size(max = 4000, message = "Description is too long")
    private String description;

    /**
     * Optional on create — defaults to "ACTIVE" server-side. Accepted values
     * match the convention already used elsewhere: ACTIVE / INACTIVE / DEPRECATED.
     */
    @Size(max = 20, message = "Status is too long")
    private String status;

    /**
     * Engine handler. {@code "LEGACY_JAVA"} (default) routes to a hand-coded
     * Java executor; any other value names a registered archetype that the
     * StrategyEngine will evaluate from {@link #specJsonb}.
     */
    @Size(max = 64, message = "Archetype is too long")
    private String archetype;

    @Positive(message = "archetypeVersion must be positive")
    private Integer archetypeVersion;

    /**
     * Full strategy specification — only meaningful for non-{@code LEGACY_JAVA}
     * archetypes. Free-form for now; structural validation arrives with the
     * StrategyEngine in M2.
     */
    private Map<String, Object> specJsonb;

    @Positive(message = "specSchemaVersion must be positive")
    private Integer specSchemaVersion;

    /** V40 — definition-scope kill-switch. Defaults to {@code false} (disabled
     *  until explicitly enabled). Set to {@code true} to enable on creation. */
    private Boolean enabled;

    /** V40 — definition-scope paper flag. Defaults to {@code true} (all new
     *  definitions are simulated/paper until explicitly set to false). */
    private Boolean simulated;
}
