package id.co.blackheart.service.user;

import id.co.blackheart.dto.request.CreateAccountRequest;
import id.co.blackheart.dto.request.RotateAccountCredentialsRequest;
import id.co.blackheart.dto.request.UpdateAccountRequest;
import id.co.blackheart.dto.response.AccountSummaryResponse;
import id.co.blackheart.exception.UserAlreadyExistsException;
import id.co.blackheart.model.Account;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.audit.AuditService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

import static id.co.blackheart.util.AppConstant.ACCOUNT_NOT_FOUND_PREFIX;

/**
 * Queries + creation for a user's exchange accounts.
 * Used by the frontend account switcher + "Add account" dialog.
 * Summary responses never expose API keys/secrets.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountQueryService {

    private final AccountRepository accountRepository;
    private final TradesRepository tradesRepository;
    private final AuditService auditService;

    /** Generic message used for both per-user-conflict and (rare) race-condition
     *  unique-violation paths. Generic on purpose - no echoing the requested
     *  username back, which would leak existence to other tenants if the
     *  uniqueness ever became cross-user again. */
    private static final String DUPLICATE_LABEL_MSG = "That label is already in use on your account.";

    /** Char-flag value mirrors what existing rows store. Soft-delete sets it to "0". */
    private static final String INACTIVE_FLAG = "0";

    /**
     * Risk defaults for freshly-created accounts. The NewAccountDialog
     * doesn't collect these — they're sensible starting values that the user
     * can tune later from account settings. Matches what existing rows hold.
     */
    private static final BigDecimal DEFAULT_RISK_AMOUNT = new BigDecimal("1.00");
    private static final BigDecimal DEFAULT_CONFIDENCE = new BigDecimal("0.70");
    private static final BigDecimal DEFAULT_TAKE_PROFIT = new BigDecimal("2.00");
    private static final BigDecimal DEFAULT_STOP_LOSS = new BigDecimal("1.00");
    /** Char flag mirrors existing rows; see {@code Account.isActive} ("1"/"0"). */
    private static final String ACTIVE_FLAG = "1";

    // Risk-policy defaults. The DB columns have NOT NULL + DEFAULT clauses,
    // but Hibernate generates an INSERT with these fields explicitly set to
    // NULL when the entity values are null - which then trips the NOT NULL
    // (Postgres column DEFAULTs are only used when the column is omitted
    // from the INSERT, not when it's explicitly NULL). Easiest fix: mirror
    // the schema defaults at the Java side.
    private static final int        DEFAULT_MAX_CONCURRENT_LONGS  = 2;
    private static final int        DEFAULT_MAX_CONCURRENT_SHORTS = 2;
    private static final boolean    DEFAULT_VOL_TARGETING_ENABLED = false;
    private static final BigDecimal DEFAULT_BOOK_VOL_TARGET_PCT   = new BigDecimal("15.00");

    /** Upper bound on per-direction and total concurrency caps. */
    private static final int MAX_CONCURRENT_CAP = 20;
    /** Upper bound (inclusive) for {@code book_vol_target_pct}. The lower
     *  bound is open (>0) — see {@link #applyBookVolTarget}. */
    private static final BigDecimal MAX_BOOK_VOL_TARGET_PCT = new BigDecimal("50");

    @Transactional(readOnly = true)
    public List<AccountSummaryResponse> getAccountsByUser(UUID userId) {
        return accountRepository.findByUserId(userId)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountSummaryResponse getAccountForUser(UUID userId, UUID accountId) {
        return toSummary(loadOwned(userId, accountId));
    }

    /**
     * Creates a new exchange account for {@code userId}. API key + secret
     * arrive as plaintext over HTTPS and are encrypted at rest by
     * {@link id.co.blackheart.converter.EncryptedStringConverter}.
     *
     * <p>Throws {@link UserAlreadyExistsException} (mapped to HTTP 409 by
     * GlobalExceptionHandler) when the user already has an account with
     * the same label. Uses a generic message that never echoes the requested
     * label - per-user uniqueness is an info-disclosure risk if the error
     * leaks the label back.
     *
     * <p>Atomic against concurrent submits: the precheck is best-effort;
     * a unique-constraint violation from {@link AccountRepository#save}
     * is caught and translated to the same friendly 409.
     */
    @Transactional
    public AccountSummaryResponse createAccount(UUID userId, CreateAccountRequest request) {
        // Username deliberately omitted from the log line - it can encode PII
        // ("rifki-savings", "alice@gmail-spot") that we don't want shipped to
        // log aggregators. accountId after save is sufficient for forensics.
        log.info("Creating account: userId={} exchange={}", userId, request.getExchange());

        if (accountRepository.existsByUserIdAndUsernameIgnoreCase(userId, request.getUsername())) {
            throw new UserAlreadyExistsException(DUPLICATE_LABEL_MSG);
        }

        Account account = new Account();
        account.setUserId(userId);
        account.setUsername(request.getUsername().trim());
        account.setExchange(request.getExchange().toUpperCase());
        account.setIsActive(ACTIVE_FLAG);
        account.setApiKey(request.getApiKey());
        account.setApiSecret(request.getApiSecret());
        account.setRiskAmount(DEFAULT_RISK_AMOUNT);
        account.setConfidence(DEFAULT_CONFIDENCE);
        account.setTakeProfit(DEFAULT_TAKE_PROFIT);
        account.setStopLoss(DEFAULT_STOP_LOSS);
        account.setMaxConcurrentLongs(DEFAULT_MAX_CONCURRENT_LONGS);
        account.setMaxConcurrentShorts(DEFAULT_MAX_CONCURRENT_SHORTS);
        account.setVolTargetingEnabled(DEFAULT_VOL_TARGETING_ENABLED);
        account.setBookVolTargetPct(DEFAULT_BOOK_VOL_TARGET_PCT);

        Account saved;
        try {
            saved = accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            // Race: a concurrent insert beat us between the precheck and save.
            // Per-user unique index (V51) trips here. Translate to friendly 409.
            throw new UserAlreadyExistsException(DUPLICATE_LABEL_MSG);
        }
        log.info("Account created: userId={} accountId={}", userId, saved.getAccountId());
        return toSummary(saved);
    }

    /**
     * Updates the label and/or exchange of an account the caller owns. Either
     * field can be null to leave it unchanged. Throws {@link UserAlreadyExistsException}
     * if the requested label is already taken by another of the caller's
     * accounts. Returns 404 (via {@link EntityNotFoundException}) if the id
     * doesn't resolve to one of the caller's accounts.
     */
    @Transactional
    public AccountSummaryResponse updateAccount(UUID userId, UUID accountId, UpdateAccountRequest req) {
        Account account = loadOwned(userId, accountId);

        boolean changed = false;
        if (req.getUsername() != null) {
            String candidate = req.getUsername().trim();
            // No-op when the label matches the existing one (case-sensitive
            // match - "Main" -> "Main" is a no-op; "main" -> "Main" is a real
            // change so we still run the conflict check).
            if (!candidate.equals(account.getUsername())) {
                if (accountRepository.existsByUserIdAndUsernameIgnoreCaseExcludingId(
                        userId, candidate, accountId)) {
                    throw new UserAlreadyExistsException(DUPLICATE_LABEL_MSG);
                }
                account.setUsername(candidate);
                changed = true;
            }
        }
        if (req.getExchange() != null) {
            String e = req.getExchange().toUpperCase();
            if (!e.equals(account.getExchange())) {
                account.setExchange(e);
                changed = true;
            }
        }

        if (!changed) {
            return toSummary(account);
        }

        Account saved;
        try {
            saved = accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            throw new UserAlreadyExistsException(DUPLICATE_LABEL_MSG);
        }
        log.info("Account updated: userId={} accountId={}", userId, accountId);
        return toSummary(saved);
    }

    /**
     * Soft-deletes an account by flipping {@code is_active} to "0". Refuses
     * if any open / partially-closed trades reference this account - those
     * are live exposure that has to be closed first.
     *
     * <p>Soft-delete (not hard-delete) preserves FK integrity for historical
     * trades, P&amp;L curves, and audit rows that reference {@code accountId}.
     */
    @Transactional
    public void softDeleteAccount(UUID userId, UUID accountId) {
        Account account = loadOwned(userId, accountId);
        long openTrades = tradesRepository.countOpenByAccountId(accountId);
        if (openTrades > 0) {
            throw new IllegalStateException(
                    "Cannot delete account with " + openTrades + " open trade(s). " +
                    "Close them first.");
        }
        if (!INACTIVE_FLAG.equals(account.getIsActive())) {
            account.setIsActive(INACTIVE_FLAG);
            accountRepository.save(account);
        }
        log.info("Account soft-deleted: userId={} accountId={}", userId, accountId);
    }

    /**
     * Atomically replaces the stored Binance API key + secret for an account
     * the caller owns. Both fields pass through
     * {@link id.co.blackheart.converter.EncryptedStringConverter} so the new
     * pair is re-encrypted at rest just like create.
     *
     * <p>Ownership is enforced by looking up the row and comparing
     * {@code userId} — a 404 is returned for both "not found" and "not yours"
     * so the endpoint doesn't leak the existence of other users' accounts.
     */
    @Transactional
    public AccountSummaryResponse rotateCredentials(
            UUID userId, UUID accountId, RotateAccountCredentialsRequest request) {
        Account account = loadOwned(userId, accountId);

        log.info("Rotating credentials: userId={} accountId={}", userId, accountId);
        account.setApiKey(request.getApiKey());
        account.setApiSecret(request.getApiSecret());
        Account saved = accountRepository.save(account);
        return toSummary(saved);
    }

    private AccountSummaryResponse toSummary(Account a) {
        return AccountSummaryResponse.builder()
                .accountId(a.getAccountId())
                .userId(a.getUserId())
                .username(a.getUsername())
                .exchange(a.getExchange())
                .isActive(a.getIsActive())
                .createdTime(a.getCreatedTime())
                .maxConcurrentLongs(a.getMaxConcurrentLongs())
                .maxConcurrentShorts(a.getMaxConcurrentShorts())
                .maxConcurrentTrades(a.getMaxConcurrentTrades())
                .volTargetingEnabled(a.getVolTargetingEnabled())
                .bookVolTargetPct(a.getBookVolTargetPct())
                .build();
    }

    /**
     * Update the per-account risk-policy levers — concurrency caps and
     * vol-targeting toggle/target. Validates the toggle pair (target must
     * be in [1, 50] when on) and rejects negative concurrency caps.
     */
    @Transactional
    public AccountSummaryResponse updateRiskConfig(UUID userId, UUID accountId, RiskConfigRequest req) {
        Account account = loadOwned(userId, accountId);
        // Snapshot the risk fields BEFORE we mutate so the audit JSON shows
        // the diff. Cloning the whole entity would also drag in API keys
        // we never want in the audit log; the inline record keeps the
        // payload narrow and forensics-friendly.
        RiskConfigSnapshot before = RiskConfigSnapshot.of(account);

        applyIntCap(req.getMaxConcurrentLongs(), "maxConcurrentLongs", account::setMaxConcurrentLongs);
        applyIntCap(req.getMaxConcurrentShorts(), "maxConcurrentShorts", account::setMaxConcurrentShorts);
        applyTradesCap(req.getMaxConcurrentTrades(), account);
        if (req.getVolTargetingEnabled() != null) {
            account.setVolTargetingEnabled(req.getVolTargetingEnabled());
        }
        applyBookVolTarget(req.getBookVolTargetPct(), account);

        Account saved = accountRepository.save(account);
        auditService.recordEvent(userId, "ACCOUNT_RISK_UPDATED", "Account",
                accountId, before, RiskConfigSnapshot.of(saved));
        return toSummary(saved);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Resolve an account by id and confirm ownership in one shot.
     *
     * <p>Both "row missing" and "wrong owner" raise the same
     * {@link EntityNotFoundException} with the same message — preserves the
     * existence-oracle invariant documented on
     * {@link id.co.blackheart.util.AppConstant#ACCOUNT_NOT_FOUND_PREFIX}.
     * Replaces five copies of the same find-then-check pattern.
     */
    private Account loadOwned(UUID userId, UUID accountId) {
        Account account = accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new EntityNotFoundException(ACCOUNT_NOT_FOUND_PREFIX + accountId));
        if (!userId.equals(account.getUserId())) {
            throw new EntityNotFoundException(ACCOUNT_NOT_FOUND_PREFIX + accountId);
        }
        return account;
    }

    /**
     * Validate-and-set for an Integer concurrency cap. {@code null} value is
     * a no-op (request didn't touch the field). Out-of-range values raise
     * {@link IllegalArgumentException} with the message format the existing
     * API contract uses ({@code "<field> must be between 0 and 20"}).
     */
    private static void applyIntCap(Integer value, String fieldName, IntConsumer setter) {
        if (value == null) return;
        if (value < 0 || value > MAX_CONCURRENT_CAP) {
            throw new IllegalArgumentException(
                    fieldName + " must be between 0 and " + MAX_CONCURRENT_CAP);
        }
        setter.accept(value);
    }

    /**
     * Validate-and-set for the total {@code maxConcurrentTrades} cap. Differs
     * from {@link #applyIntCap} because values {@code < 1} clear the cap
     * (stored as DB NULL) instead of being rejected — this is the wire-level
     * sentinel the frontend uses to "remove the limit". Upper bound mirrors
     * the per-direction caps.
     */
    private static void applyTradesCap(Integer value, Account account) {
        if (value == null) return;
        if (value > MAX_CONCURRENT_CAP) {
            throw new IllegalArgumentException(
                    "maxConcurrentTrades must be between 0 and " + MAX_CONCURRENT_CAP);
        }
        account.setMaxConcurrentTrades(value < 1 ? null : value);
    }

    /** Validate-and-set for {@code bookVolTargetPct}. Range is (0, 50]. */
    private static void applyBookVolTarget(BigDecimal value, Account account) {
        if (value == null) return;
        if (value.signum() <= 0 || value.compareTo(MAX_BOOK_VOL_TARGET_PCT) > 0) {
            throw new IllegalArgumentException(
                    "bookVolTargetPct must be in (0, " + MAX_BOOK_VOL_TARGET_PCT.toPlainString() + "]");
        }
        account.setBookVolTargetPct(value);
    }

    /** Narrow snapshot of audit-relevant risk fields — never includes credentials. */
    private record RiskConfigSnapshot(
            Integer maxConcurrentLongs,
            Integer maxConcurrentShorts,
            Integer maxConcurrentTrades,
            Boolean volTargetingEnabled,
            BigDecimal bookVolTargetPct
    ) {
        static RiskConfigSnapshot of(Account a) {
            return new RiskConfigSnapshot(
                    a.getMaxConcurrentLongs(),
                    a.getMaxConcurrentShorts(),
                    a.getMaxConcurrentTrades(),
                    a.getVolTargetingEnabled(),
                    a.getBookVolTargetPct()
            );
        }
    }

    /** Inline DTO so we don't need a fresh top-level request file. */
    @lombok.Data
    public static class RiskConfigRequest {
        private Integer maxConcurrentLongs;
        private Integer maxConcurrentShorts;
        /**
         * Total concurrent open trades across all strategies. {@code null}
         * leaves the field unchanged. Pass {@code 0} (or any value &lt; 1)
         * to clear the cap; values 1–20 set a hard total cap.
         */
        private Integer maxConcurrentTrades;
        private Boolean volTargetingEnabled;
        private BigDecimal bookVolTargetPct;
    }
}
