package id.co.blackheart.dto;

import lombok.*;

import java.math.BigDecimal;

@Setter
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderSummary {
    private BigDecimal totalQty;
    private BigDecimal totalCost;
    private BigDecimal totalFee;
    private String feeCurrency;
}
