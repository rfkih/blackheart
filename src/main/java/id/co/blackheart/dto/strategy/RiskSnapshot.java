package id.co.blackheart.dto.strategy;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class RiskSnapshot {

    private BigDecimal baseRiskPct;
    private BigDecimal finalRiskPct;
    private BigDecimal riskMultiplier;
    private BigDecimal maxAllowedPositionSize;

    private Map<String, Object> diagnostics;
}