package id.co.blackheart.dto.strategy;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class VolatilitySnapshot {

    private BigDecimal atr;
    private BigDecimal atrPercentile;
    private BigDecimal realizedVol;
    private BigDecimal forecastVol;
    private BigDecimal jumpRiskScore;

    private Map<String, Object> diagnostics;
}