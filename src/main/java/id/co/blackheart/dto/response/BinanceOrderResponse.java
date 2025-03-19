package id.co.blackheart.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class BinanceOrderResponse {
    private String symbol;
    private Long orderId;
    private Long orderListId;
    private String clientOrderId;
    private Long transactTime;
    private String price;
    private String origQty;
    private String executedQty;
    private String origQuoteOrderQty;
    private String cummulativeQuoteQty;
    private String status;
    private String timeInForce;
    private String type;
    private String side;
    private Long workingTime;
    private List<BinanceOrderFill> fills;
    private String selfTradePreventionMode;
}
