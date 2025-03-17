package id.co.blackheart.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;


@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class TokocryptoResponse {

    @JsonProperty("code")
    private int code;

    @JsonProperty("msg")
    private String msg;

    @JsonProperty("data")
    private JsonNode data;

    @JsonProperty("timestamp")
    private long timestamp;
}




