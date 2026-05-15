package id.co.blackheart.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code POST /api/v1/users/password-reset/confirm}. The token is
 * the raw value from the reset URL; the new password is validated for
 * minimum length only (other complexity rules can come later).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetConfirmRequest {

    @NotBlank
    @Size(max = 128)
    private String token;

    @NotBlank
    @Size(min = 8, max = 200, message = "password must be at least 8 characters")
    private String newPassword;
}
