package id.co.blackheart.service.user;

import id.co.blackheart.dto.response.AccountSummaryResponse;
import id.co.blackheart.model.Account;
import id.co.blackheart.repository.AccountRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only queries for a user's exchange accounts.
 * Used by the frontend account switcher to build the per-account scoping UI.
 * Never exposes API keys/secrets.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountQueryService {

    private final AccountRepository accountRepository;

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
