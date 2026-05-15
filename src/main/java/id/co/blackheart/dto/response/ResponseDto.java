package id.co.blackheart.dto.response;

import id.co.blackheart.util.ResponseCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class ResponseDto implements Serializable {

    private String responseCode;

    @Builder.Default
    private String responseDesc = ResponseCode.SUCCESS.getDescription();

    // ResponseDto is a REST envelope; serialisation here is JSON via Jackson, never
    // ObjectOutputStream. Marking the polymorphic data field transient satisfies
    // S1948 without affecting wire behaviour — Jackson ignores `transient` by default.
    private transient Object data;

    private String errorMessage;
}
