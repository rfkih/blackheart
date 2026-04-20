package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class PortfolioBalanceResponse {
    private UUID accountId;
    private BigDecimal totalUsdt;
    private BigDecimal availableUsdt;
    private BigDecimal lockedUsdt;
    private List<PortfolioAssetResponse> assets;
}
