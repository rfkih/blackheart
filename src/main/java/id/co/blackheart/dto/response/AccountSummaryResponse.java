package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight account metadata for the frontend account switcher.
 * Excludes API key/secret — this is a public-facing summary.
 */
@Data
@Builder
public class AccountSummaryResponse {

    private UUID accountId;
    private UUID userId;
    private String username;
    private String exchange;
    /** "Y" / "N" — mirrors Account.is_active (raw value). */
    private String isActive;
    private LocalDateTime createdTime;
    /** Phase 2a — concurrency caps (long / short). */
    private Integer maxConcurrentLongs;
    private Integer maxConcurrentShorts;
    /** Phase 2b — vol-targeting toggle + target. */
    private Boolean volTargetingEnabled;
    private BigDecimal bookVolTargetPct;
}
