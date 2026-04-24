package id.co.blackheart.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterUserRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 255, message = "Email cannot exceed 255 characters")
    private String email;

    /**
     * Password complexity policy:
     * <ul>
     *   <li>12–100 characters</li>
     *   <li>At least one lowercase letter</li>
     *   <li>At least one uppercase letter</li>
     *   <li>At least one digit</li>
     *   <li>At least one symbol (non-word, non-whitespace)</li>
     * </ul>
     * Enforced at registration only — legacy accounts created under the old
     * 8-char policy remain valid until the user next rotates the password.
     */
    @NotBlank(message = "Password is required")
    @Size(min = 12, max = 100, message = "Password must be between 12 and 100 characters")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^\\w\\s]).+$",
            message = "Password must contain at least one lowercase letter, one uppercase letter, one digit, and one symbol"
    )
    private String password;

    @NotBlank(message = "Full name is required")
    @Size(max = 255, message = "Full name cannot exceed 255 characters")
    private String fullName;

    @Size(max = 30, message = "Phone number cannot exceed 30 characters")
    private String phoneNumber;
}
