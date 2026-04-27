package id.co.blackheart.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code POST /api/v1/users/password-reset/request}. The
 * controller never tells the caller whether the email exists — same
 * response either way — so this DTO carries only the email itself.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetRequestRequest {

    @NotBlank
    @Email(message = "must be a valid email")
    private String email;
}
