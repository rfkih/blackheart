package id.co.blackheart.service.user;

import id.co.blackheart.dto.request.CreateAccountRequest;
import id.co.blackheart.dto.response.AccountSummaryResponse;
import id.co.blackheart.exception.UserAlreadyExistsException;
import id.co.blackheart.model.Account;
import id.co.blackheart.repository.AccountRepository;
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

    private AccountSummaryResponse toSummary(Account a) {
        return AccountSummaryResponse.builder()
                .accountId(a.getAccountId())
                .userId(a.getUserId())
                .username(a.getUsername())
                .exchange(a.getExchange())
                .isActive(a.getIsActive())
                .createdTime(a.getCreatedTime())
                .build();
    }
}
