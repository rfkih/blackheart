package id.co.blackheart.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for {@code POST /api/v1/accounts} - user supplies their exchange API
 * credentials so the platform can trade on their behalf.
 *
 * <p>API key / secret arrive as plaintext over HTTPS; the service encrypts
 * them at rest. Never log this object - the logging layer redacts any field
 * whose name contains {@code secret} or {@code apiKey}, but defence-in-depth
 * is cheap.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountRequest {

    /**
     * Display label for the account. Unique per user. Kept short because it
     * shows up in the in-app account-switcher.
     */
    @NotBlank(message = "Username is required")
    @Size(min = 2, max = 50, message = "Username must be between 2 and 50 characters")
    @Pattern(
            regexp = "^[A-Za-z0-9 _-]+$",
            message = "Username may only contain letters, digits, spaces, underscores, and hyphens"
    )
    private String username;

    /**
     * Three-letter exchange code - matches the DB column length. Only
     * Binance variants are supported today; "BIN" for spot, "BIF" for
     * USD-M futures.
     */
    @NotBlank(message = "Exchange is required")
    @Pattern(
            regexp = "^(BIN|BIF)$",
            message = "Exchange must be one of: BIN, BIF"
    )
    private String exchange;

    /**
     * Binance API key. Real Binance keys are 64 alphanumeric characters - we
     * allow 60-80 to leave headroom for variants. Strict format gate is FE+BE
     * defense-in-depth: stops obviously-bad pastes (extra whitespace, leaked
     * "sk-..." OpenAI keys, etc.) before they reach the encryption layer.
     */
    @NotBlank(message = "API key is required")
    @Pattern(
            regexp = "^[A-Za-z0-9]{60,80}$",
            message = "API key must be 60-80 alphanumeric characters"
    )
    private String apiKey;

    @NotBlank(message = "API secret is required")
    @Pattern(
            regexp = "^[A-Za-z0-9]{60,80}$",
            message = "API secret must be 60-80 alphanumeric characters"
    )
    private String apiSecret;

    /**
     * Server-side gate on the safety acknowledgement the UI also enforces.
     * Required to be true. The frontend checkbox is the user-visible copy of
     * this; this field stops a direct curl from skipping the gate.
     *
     * <p>Defaults to {@code Boolean.FALSE} (not the unboxed false) so the
     * @AssertTrue check fires even when the field is omitted from the JSON
     * payload entirely.
     */
    @AssertTrue(message = "You must confirm withdrawal permissions are disabled on this API key")
    private Boolean acknowledgedSafety = Boolean.FALSE;
}
