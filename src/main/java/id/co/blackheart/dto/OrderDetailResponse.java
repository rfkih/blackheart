package id.co.blackheart.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderDetailResponse {

    @JsonProperty("orderId")
    private String orderId;

    @JsonProperty("clientId")
    private String clientId;

    @JsonProperty("bOrderId")
    private String bOrderId;

    @JsonProperty("bOrderListId")
    private String bOrderListId;

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

    @JsonProperty("time")
    private String time;

    @JsonProperty("taxFee")
    private String taxFee;

    @JsonProperty("cfxFee")
    private String cfxFee;

    @JsonProperty("taxFeeAsset")
    private String taxFeeAsset;

    @JsonProperty("createTime")
    private String createTime;
}
