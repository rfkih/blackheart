package id.co.blackheart.dto.strategy;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class RegimeSnapshot {

    private String regimeLabel;
    private BigDecimal trendScore;
    private BigDecimal compressionScore;
    private BigDecimal regimeConfidence;

    private Map<String, Object> diagnostics;
}