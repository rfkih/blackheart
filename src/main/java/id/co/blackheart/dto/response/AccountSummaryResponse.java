package id.co.blackheart.dto.response;

import lombok.Builder;
import lombok.Data;

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
}
