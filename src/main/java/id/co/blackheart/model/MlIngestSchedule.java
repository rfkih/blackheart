package id.co.blackheart.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Admin-configurable per-source cron schedule for live ML ingestion. One row
 * per {@code (source, symbol)} tuple — {@code symbol} is NULL for macro-only
 * sources (fred, alternative_me, forexfactory).
 *
 * <p>Edited from the Blackridge admin UI. Spring {@code TaskScheduler} reads
 * this table on a 60s refresh tick and registers dynamic {@code CronTrigger}
 * tasks; changes take effect on the next refresh. All schedules begin
 * {@code enabled=false} so the operator can roll out one source at a time
 * after the matching Python module is verified.
 *
 * <p><b>Cron format</b>: Spring 6-field expression
 * (sec min hour day month dow). Validated server-side via
 * {@code CronExpression.parse()} before persist.
 *
 * <p><b>Config JSONB</b>: source-specific params, e.g.
 * {@code {"series_ids": ["DXY","DGS10"]}} for fred,
 * {@code {"feeds": ["funding_rate","open_interest"]}} for binance_macro.
 * Schema enforced by each Python source module.
 */
@Entity
@Table(name = "ml_ingest_schedule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MlIngestSchedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "source", nullable = false, length = 80)
    private String source;

    @Column(name = "symbol", length = 20)
    private String symbol;

    @Column(name = "cron_expression", nullable = false, length = 80)
    private String cronExpression;

    @Column(name = "lookback_hours", nullable = false)
    private Integer lookbackHours;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private JsonNode config;
}
