package id.co.blackheart.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TradeMessage {
    private String eType;  // Event type
    private long ETime;    // Event time
    private String s;  // Symbol
    private String p;  // Price
    private String q;  // Quantity
    private long T;    // Trade time
}
