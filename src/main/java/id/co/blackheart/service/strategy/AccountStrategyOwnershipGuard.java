package id.co.blackheart.service.strategy;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.AccountStrategyRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Centralized ownership gate for per-account-strategy operations. Use in every
 * controller/service path that accepts an {@code accountStrategyId} from the client,
 * before performing any read or write. Returns the same generic 404 for both
 * "not found" and "not yours" to avoid leaking existence via error-message oracles.
 */
@Component
@RequiredArgsConstructor
public class AccountStrategyOwnershipGuard {

    private final AccountStrategyRepository accountStrategyRepository;
    private final AccountRepository accountRepository;

    public AccountStrategy assertOwned(UUID userId, UUID accountStrategyId) {
        if (userId == null || accountStrategyId == null) {
            throw new EntityNotFoundException("Not found");
        }
        AccountStrategy strategy = accountStrategyRepository.findById(accountStrategyId)
                .orElseThrow(() -> new EntityNotFoundException("Not found"));

        if (Boolean.TRUE.equals(strategy.getIsDeleted())) {
            throw new EntityNotFoundException("Not found");
        }

        Account account = accountRepository.findByAccountId(strategy.getAccountId())
                .orElseThrow(() -> new EntityNotFoundException("Not found"));

        if (!userId.equals(account.getUserId())) {
            throw new EntityNotFoundException("Not found");
        }

        return strategy;
    }

    public void assertOwnsAccount(UUID userId, UUID accountId) {
        if (userId == null || accountId == null) {
            throw new EntityNotFoundException("Not found");
        }
        Account account = accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Not found"));
        if (!userId.equals(account.getUserId())) {
            throw new EntityNotFoundException("Not found");
        }
    }
}
