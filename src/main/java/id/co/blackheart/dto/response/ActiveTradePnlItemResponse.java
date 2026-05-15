package id.co.blackheart.dto.response;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveTradePnlItemResponse {
    private UUID tradeId;
    private String asset;
    private String side;
    private String status;

    private String avgEntryPrice;
    private String currentPrice;
    private String totalRemainingQty;

    private String unrealizedPnlAmount;
    private String unrealizedPnlPercent;
}