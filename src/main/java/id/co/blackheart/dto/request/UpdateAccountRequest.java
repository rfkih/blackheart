package id.co.blackheart.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for {@code PATCH /api/v1/accounts/{id}} - rename + change exchange.
 * Both fields optional; null means "leave unchanged".
 *
 * <p>Credentials are NOT here - rotation goes through the dedicated
 * {@code PATCH /accounts/{id}/credentials} endpoint so audit trails and
 * encryption-key handling stay scoped.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAccountRequest {

    @Size(min = 2, max = 50, message = "Username must be between 2 and 50 characters")
    @Pattern(
            regexp = "^[A-Za-z0-9 _-]+$",
            message = "Username may only contain letters, digits, spaces, underscores, and hyphens"
    )
    private String username;

    @Pattern(
            regexp = "^(BIN|BIF)$",
            message = "Exchange must be one of: BIN, BIF"
    )
    private String exchange;
}
