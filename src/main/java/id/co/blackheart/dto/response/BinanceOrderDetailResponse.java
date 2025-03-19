package id.co.blackheart.dto.response;

import lombok.Data;

@Data
public class BinanceOrderDetailResponse {
    private String symbol;
    private Long orderId;
    private Long orderListId;
    private String clientOrderId;
    private String price;
    private String origQty;
    private String executedQty;
    private String cummulativeQuoteQty;
    private String status;
    private String timeInForce;
    private String type;
    private String side;
    private String stopPrice;
    private String icebergQty;
    private Long time;
    private Long updateTime;
    private Boolean isWorking;
    private Long workingTime;
    private String origQuoteOrderQty;
    private String selfTradePreventionMode;
}

