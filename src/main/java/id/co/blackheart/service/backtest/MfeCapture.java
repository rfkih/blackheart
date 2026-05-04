package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * How much of the available move the runner managed to capture.
 * Signals whether trail width is leaving meat on the table.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MfeCapture {
    private BigDecimal winnerCaptureAvg;
    private BigDecimal winnerCaptureMin;
    private BigDecimal winnerCaptureMax;
    private BigDecimal winnerMfeAvg;
    private BigDecimal loserMaeAvg;
    private BigDecimal loserMaeMedian;
}
