package id.co.blackheart.dto.strategy;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class MarketQualitySnapshot {

    private BigDecimal liquidityScore;
    private BigDecimal volumeScore;
    private BigDecimal executionQualityScore;
    private Boolean tradable;

    private Map<String, Object> diagnostics;
}