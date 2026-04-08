package id.co.blackheart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveTradePnlResponse {
    private UUID accountId;
    private String totalUnrealizedPnlAmount;
    private List<ActiveTradePnlItemResponse> trades;
}