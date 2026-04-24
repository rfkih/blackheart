package id.co.blackheart.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for {@code PATCH /api/v1/accounts/{id}/credentials}.
 *
 * <p>Binance does not support mutating an existing API key in place — rotation
 * means generating a fresh key+secret pair on the exchange side and pushing
 * both here. The service persists them atomically through
 * {@link id.co.blackheart.converter.EncryptedStringConverter} so the pair
 * never sits in plaintext at rest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RotateAccountCredentialsRequest {

    @NotBlank(message = "API key is required")
    @Size(max = 255, message = "API key is too long")
    private String apiKey;

    @NotBlank(message = "API secret is required")
    @Size(max = 255, message = "API secret is too long")
    private String apiSecret;
}
