package id.co.blackheart.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MarketOrderResponse {

    @JsonProperty("orderId")
    private Long orderId;

    @JsonProperty("clientId")
    private String clientId;

    @JsonProperty("symbol")
    private String symbol;

    @JsonProperty("symbolType")
    private int symbolType;

    @JsonProperty("side")
    private int side;

    @JsonProperty("type")
    private int type;

    @JsonProperty("price")
    private String price;

    @JsonProperty("origQty")
    private String origQty;

    @JsonProperty("origQuoteQty")
    private String origQuoteQty;

    @JsonProperty("executedQty")
    private String executedQty;

    @JsonProperty("executedPrice")
    private String executedPrice;

    @JsonProperty("executedQuoteQty")
    private String executedQuoteQty;

    @JsonProperty("timeInForce")
    private int timeInForce;

    @JsonProperty("stopPrice")
    private String stopPrice;

    @JsonProperty("icebergQty")
    private String icebergQty;

    @JsonProperty("status")
    private int status;

    @JsonProperty("isWorking")
    private int isWorking;

    @JsonProperty("createTime")
    private Long createTime;

    @JsonProperty("engineHeaders")
    private Map<String, String[]> engineHeaders;

    @JsonProperty("borderListId")
    private int borderListId;

    @JsonProperty("borderId")
    private String borderId;
}
