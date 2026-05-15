package id.co.blackheart.util;

/**
 * Cross-cutting application constants.
 *
 * <p>Holds string literals and other primitives that would otherwise be
 * duplicated across services / controllers and trip Sonar's S1192
 * (duplicate-string-literal) rule. Add a constant here when the same
 * literal appears in 3+ files; literals scoped to a single class belong
 * inside that class as a {@code private static final}, not here.
 *
 * <p>Keep this class lean — it is a global vocabulary, not a junk drawer.
 * Domain-specific constants live in their own focused classes
 * (e.g. {@link TradeConstant}, {@link HeaderName}, {@link ResponseCode}).
 */
public final class AppConstant {

    /** Suppresses the implicit public constructor — utility class only. */
    private AppConstant() {
    }

    // ── Error-message prefixes ──────────────────────────────────────────

    /**
     * Prefix used when raising {@link jakarta.persistence.EntityNotFoundException}
     * for missing or non-owned account rows. Call sites use
     * {@code ACCOUNT_NOT_FOUND_PREFIX + accountId} so the trailing space +
     * colon are baked in.
     *
     * <p>The wording is intentionally identical between "row missing" and
     * "wrong owner" paths so error responses don't leak account existence
     * cross-user — do not split this into two messages without first
     * reviewing the existence-oracle implication.
     */
    public static final String ACCOUNT_NOT_FOUND_PREFIX = "Account not found: ";

    /**
     * Generic 404 message used by ownership / readability guards. Identical
     * wording on "row missing", "soft-deleted", and "wrong owner" branches
     * is deliberate — splitting it would leak existence cross-user via an
     * error-message oracle. Keep it generic.
     */
    public static final String NOT_FOUND = "Not found";

    // ── Audit entity type tags ──────────────────────────────────────────

    /**
     * Logical entity type recorded on {@link id.co.blackheart.model.AuditEvent}
     * rows whose target is an {@code account_strategy} row. Used by every
     * service that mutates account-strategy state (create, activate,
     * update, clone, promote, delete, kill-switch). Keep the spelling in
     * sync — audit consumers filter on the exact string.
     */
    public static final String ENTITY_ACCOUNT_STRATEGY = "AccountStrategy";
}
