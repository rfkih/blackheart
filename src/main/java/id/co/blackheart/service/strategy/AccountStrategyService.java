package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.response.AccountStrategyResponse;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.AccountStrategyRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountStrategyService {

    private final AccountRepository accountRepository;
    private final AccountStrategyRepository accountStrategyRepository;

    /**
     * Returns all account strategies belonging to every account owned by the given user.
     */
    @Transactional(readOnly = true)
    public List<AccountStrategyResponse> getStrategiesByUser(UUID userId) {
        List<Account> accounts = accountRepository.findByUserId(userId);
        if (accounts.isEmpty()) {
            log.debug("No accounts found for userId={}", userId);
            return List.of();
        }

        return accounts.stream()
                .flatMap(account -> accountStrategyRepository
                        .findByAccountId(account.getAccountId())
                        .stream())
                .map(this::toResponse)
                .toList();
    }

    /**
     * Returns a single account strategy by id, verifying that its account belongs to the user.
     */
    @Transactional(readOnly = true)
    public AccountStrategyResponse getStrategyById(UUID userId, UUID accountStrategyId) {
        AccountStrategy strategy = accountStrategyRepository.findById(accountStrategyId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Account strategy not found: " + accountStrategyId));

        Account account = accountRepository.findByAccountId(strategy.getAccountId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Account strategy not found: " + accountStrategyId));

        if (!userId.equals(account.getUserId())) {
            throw new EntityNotFoundException("Account strategy not found: " + accountStrategyId);
        }

        return toResponse(strategy);
    }

    /**
     * Returns all account strategies for a specific account, verifying the account belongs to the user.
     */
    @Transactional(readOnly = true)
    public List<AccountStrategyResponse> getStrategiesByUserAndAccount(UUID userId, UUID accountId) {
        Account account = accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found: " + accountId));

        if (!userId.equals(account.getUserId())) {
            throw new EntityNotFoundException("Account not found: " + accountId);
        }

        return accountStrategyRepository.findByAccountId(accountId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private AccountStrategyResponse toResponse(AccountStrategy s) {
        return AccountStrategyResponse.builder()
                .accountStrategyId(s.getAccountStrategyId())
                .accountId(s.getAccountId())
                .strategyDefinitionId(s.getStrategyDefinitionId())
                .strategyCode(s.getStrategyCode())
                .symbol(s.getSymbol())
                .intervalName(s.getIntervalName())
                .enabled(s.getEnabled())
                .allowLong(s.getAllowLong())
                .allowShort(s.getAllowShort())
                .maxOpenPositions(s.getMaxOpenPositions())
                .capitalAllocationPct(s.getCapitalAllocationPct())
                .priorityOrder(s.getPriorityOrder())
                .currentStatus(s.getCurrentStatus())
                .createdTime(s.getCreatedTime())
                .updatedTime(s.getUpdatedTime())
                .build();
    }
}
