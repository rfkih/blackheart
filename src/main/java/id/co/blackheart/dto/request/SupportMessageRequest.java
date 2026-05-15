package id.co.blackheart.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportMessageRequest {

    @NotBlank
    @Size(max = 200, message = "subject must be at most 200 characters")
    private String subject;

    @NotBlank
    @Size(min = 10, max = 5000, message = "body must be 10–5000 characters")
    private String body;

    /** Optional diagnostic snapshot copy-pasted by the user. */
    @Size(max = 2000)
    private String diagnostic;
}
