package id.co.blackheart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveTradePnlResponse {
    private UUID userId;
    private String totalUnrealizedPnlAmount;
    private List<ActiveTradePnlItemResponse> trades;
}