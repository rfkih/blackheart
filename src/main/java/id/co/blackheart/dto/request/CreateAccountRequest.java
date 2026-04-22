package id.co.blackheart.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for {@code POST /api/v1/accounts} — user supplies their exchange API
 * credentials so the platform can trade on their behalf.
 *
 * <p>API key / secret arrive as plaintext over HTTPS; the service encrypts
 * them at rest. Never log this object — the logging layer redacts any field
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
     * Three-letter exchange code — matches the DB column length. Only
     * Binance variants are supported today; `"BIN"` for spot, `"BIF"` for
     * USD-M futures. Kept as a plain string (not an enum) so adding a new
     * venue doesn't require a schema migration.
     */
    @NotBlank(message = "Exchange is required")
    @Size(min = 3, max = 3, message = "Exchange must be a 3-letter code")
    private String exchange;

    @NotBlank(message = "API key is required")
    @Size(max = 255, message = "API key is too long")
    private String apiKey;

    @NotBlank(message = "API secret is required")
    @Size(max = 255, message = "API secret is too long")
    private String apiSecret;
}
