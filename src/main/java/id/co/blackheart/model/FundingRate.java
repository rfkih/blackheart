package id.co.blackheart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One Binance perpetual-futures 8h funding settlement event for a symbol.
 * Composite key (symbol, fundingTime) — see {@link FundingRateId}. Phase 4
 * step 1 — backfilled from Binance fapi, ingested live by an 8-hourly
 * scheduler (Phase 4.8).
 */
@Entity
@Table(name = "funding_rate_history")
@IdClass(FundingRate.FundingRateId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundingRate {

    @Id
    @Column(name = "symbol", length = 20, nullable = false)
    private String symbol;

    @Id
    @Column(name = "funding_time", nullable = false)
    private LocalDateTime fundingTime;

    @Column(name = "funding_rate", nullable = false, precision = 18, scale = 10)
    @SuppressWarnings("java:S1700") // Schema-mapped column; renaming cascades to 30+ caller sites for no semantic gain.
    private BigDecimal fundingRate;

    @Column(name = "mark_price", precision = 38, scale = 8)
    private BigDecimal markPrice;

    @Column(name = "created_time", nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdTime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FundingRateId implements Serializable {
        private String symbol;
        private LocalDateTime fundingTime;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FundingRateId that)) return false;
            return Objects.equals(symbol, that.symbol)
                    && Objects.equals(fundingTime, that.fundingTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(symbol, fundingTime);
        }
    }
}
