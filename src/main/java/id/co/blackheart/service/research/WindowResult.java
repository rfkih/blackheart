package id.co.blackheart.service.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One walk-forward fold's metrics for a single combo. K of these per combo
 * when {@link SweepSpec#getSplitMode()} is {@code WALK_FORWARD_K}.
 *
 * <p>Each fold is an independent train + OOS pair. OOS slices across folds
 * are non-overlapping by construction so a strategy can't double-count test
 * data; train slices are expanding-anchored so each fold sees all prior
 * history (mirrors how a live strategy would actually run).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WindowResult {

    /** 1-based ordinal across the K folds for this combo. */
    private Integer foldIndex;

    private LocalDateTime trainFromDate;
    private LocalDateTime trainToDate;
    private LocalDateTime oosFromDate;
    private LocalDateTime oosToDate;

    private UUID trainBacktestRunId;
    private UUID oosBacktestRunId;

    /** Annualized Sharpe (× √252) on the train slice. */
    private BigDecimal trainSharpeRatio;

    /** Annualized Sharpe on the OOS slice — the honest signal. */
    private BigDecimal oosSharpeRatio;
    private BigDecimal oosPsr;
    private BigDecimal oosNetPnl;
    private Integer oosTradeCount;

    /** {@code COMPLETED} | {@code FAILED}. Set per fold so a single bad fold
     *  doesn't fail the whole combo if the others succeed. */
    private String status;
}
