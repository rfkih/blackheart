package id.co.blackheart.service.user;

import id.co.blackheart.dto.request.CreateAccountRequest;
import id.co.blackheart.dto.request.RotateAccountCredentialsRequest;
import id.co.blackheart.dto.response.AccountSummaryResponse;
import id.co.blackheart.exception.UserAlreadyExistsException;
import id.co.blackheart.model.Account;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.service.audit.AuditService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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
    private final AuditService auditService;

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

    @Transactional(readOnly = true)
    public List<AccountSummaryResponse> getAccountsByUser(UUID userId) {
        return accountRepository.findByUserId(userId)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountSummaryResponse getAccountForUser(UUID userId, UUID accountId) {
        Account account = accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found: " + accountId));
        if (!userId.equals(account.getUserId())) {
            throw new EntityNotFoundException("Account not found: " + accountId);
        }
        return toSummary(account);
    }

    /**
     * Creates a new exchange account for {@code userId}. API key + secret
     * arrive as plaintext over HTTPS and are stored verbatim — adding
     * at-rest encryption is a follow-up (requires a key-management story).
     *
     * <p>Throws {@link UserAlreadyExistsException} (mapped to HTTP 409 by
     * GlobalExceptionHandler) when the username is already taken, rather
     * than letting a unique-constraint violation bubble up as a 500.
     */
    @Transactional
    public AccountSummaryResponse createAccount(UUID userId, CreateAccountRequest request) {
        log.info("Creating account: userId={} username={} exchange={}",
                userId, request.getUsername(), request.getExchange());

        if (accountRepository.existsByUsernameIgnoreCase(request.getUsername())) {
            throw new UserAlreadyExistsException(
                    "An account with username '" + request.getUsername() + "' already exists");
        }

        Account account = new Account();
        account.setUserId(userId);
        account.setUsername(request.getUsername());
        account.setExchange(request.getExchange().toUpperCase());
        account.setIsActive(ACTIVE_FLAG);
        account.setApiKey(request.getApiKey());
        account.setApiSecret(request.getApiSecret());
        account.setRiskAmount(DEFAULT_RISK_AMOUNT);
        account.setConfidence(DEFAULT_CONFIDENCE);
        account.setTakeProfit(DEFAULT_TAKE_PROFIT);
        account.setStopLoss(DEFAULT_STOP_LOSS);

        Account saved = accountRepository.save(account);
        log.info("Account created: accountId={}", saved.getAccountId());
        return toSummary(saved);
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
        Account account = accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found: " + accountId));
        if (!userId.equals(account.getUserId())) {
            throw new EntityNotFoundException("Account not found: " + accountId);
        }

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
        Account account = accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found: " + accountId));
        if (!userId.equals(account.getUserId())) {
            throw new EntityNotFoundException("Account not found: " + accountId);
        }
        // Snapshot the risk fields BEFORE we mutate so the audit JSON shows
        // the diff. Cloning the whole entity would also drag in API keys
        // we never want in the audit log; the inline record keeps the
        // payload narrow and forensics-friendly.
        RiskConfigSnapshot before = RiskConfigSnapshot.of(account);
        if (req.getMaxConcurrentLongs() != null) {
            if (req.getMaxConcurrentLongs() < 0 || req.getMaxConcurrentLongs() > 20) {
                throw new IllegalArgumentException("maxConcurrentLongs must be between 0 and 20");
            }
            account.setMaxConcurrentLongs(req.getMaxConcurrentLongs());
        }
        if (req.getMaxConcurrentShorts() != null) {
            if (req.getMaxConcurrentShorts() < 0 || req.getMaxConcurrentShorts() > 20) {
                throw new IllegalArgumentException("maxConcurrentShorts must be between 0 and 20");
            }
            account.setMaxConcurrentShorts(req.getMaxConcurrentShorts());
        }
        if (req.getMaxConcurrentTrades() != null) {
            // Sentinel value 0 from the wire could mean "unset" — treat any
            // value < 1 as "clear the cap" (null in DB). Upper bound mirrors
            // the per-direction caps.
            int t = req.getMaxConcurrentTrades();
            if (t > 20) {
                throw new IllegalArgumentException("maxConcurrentTrades must be between 0 and 20");
            }
            account.setMaxConcurrentTrades(t < 1 ? null : t);
        }
        if (req.getVolTargetingEnabled() != null) {
            account.setVolTargetingEnabled(req.getVolTargetingEnabled());
        }
        if (req.getBookVolTargetPct() != null) {
            BigDecimal t = req.getBookVolTargetPct();
            if (t.signum() <= 0 || t.compareTo(new BigDecimal("50")) > 0) {
                throw new IllegalArgumentException("bookVolTargetPct must be in (0, 50]");
            }
            account.setBookVolTargetPct(t);
        }
        Account saved = accountRepository.save(account);
        auditService.record(userId, "ACCOUNT_RISK_UPDATED", "Account",
                accountId, before, RiskConfigSnapshot.of(saved));
        return toSummary(saved);
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
