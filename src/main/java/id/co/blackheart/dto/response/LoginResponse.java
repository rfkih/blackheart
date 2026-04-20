package id.co.blackheart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String accessToken;

    /** Always "Bearer" — included so clients don't need to hardcode the prefix. */
    private String tokenType;

    /** Token lifetime in seconds. */
    private long expiresIn;

    private UserResponse user;
}
