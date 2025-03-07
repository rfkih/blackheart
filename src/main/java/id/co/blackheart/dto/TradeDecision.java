package id.co.blackheart.dto;


import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Setter
@Getter
@Builder
public class TradeDecision {

    private String action;
    private BigDecimal positionSize;
    private BigDecimal stopLossPrice;
    private BigDecimal takeProfitPrice;
}
