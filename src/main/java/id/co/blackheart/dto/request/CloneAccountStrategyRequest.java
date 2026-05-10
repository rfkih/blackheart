package id.co.blackheart.dto.request;

import lombok.Data;

import java.util.UUID;

/**
 * V54 — body for POST /api/v1/account-strategies/{id}/clone.
 *
 * <p>Optional. When omitted (or when {@code targetAccountId} is null), the
 * clone lands in the caller's first account (oldest by created_time). The
 * frontend will surface a destination picker when the caller has multiple
 * accounts; for the single-account common case the body can be empty.
 */
@Data
public class CloneAccountStrategyRequest {

    /**
     * Optional target account_id for the clone. Must be owned by the calling
     * user; foreign accounts return a generic 404 to avoid existence leaks.
     * Null/omitted → defaults to the caller's first account.
     */
    private UUID targetAccountId;
}
