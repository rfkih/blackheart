package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.request.CreateAccountStrategyRequest;
import id.co.blackheart.dto.response.AccountStrategyResponse;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.StrategyDefinition;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.StrategyDefinitionRepository;
import id.co.blackheart.repository.TradesRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountStrategyService {

    private final AccountRepository accountRepository;
    private final AccountStrategyRepository accountStrategyRepository;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final TradesRepository tradesRepository;

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

        if (Boolean.TRUE.equals(strategy.getIsDeleted())) {
            throw new EntityNotFoundException("Account strategy not found: " + accountStrategyId);
        }

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

    /**
     * Creates a new account strategy, verifying the user owns the target account and that the
     * provided strategy code resolves to a real strategy definition.
     */
    @Transactional
    public AccountStrategyResponse createStrategy(UUID userId, CreateAccountStrategyRequest req) {
        Account account = accountRepository.findByAccountId(req.getAccountId())
                .orElseThrow(() -> new EntityNotFoundException("Account not found: " + req.getAccountId()));

        if (!userId.equals(account.getUserId())) {
            throw new EntityNotFoundException("Account not found: " + req.getAccountId());
        }

        StrategyDefinition def = strategyDefinitionRepository.findByStrategyCode(req.getStrategyCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown strategy code: " + req.getStrategyCode()));

        AccountStrategy entity = AccountStrategy.builder()
                .accountStrategyId(UUID.randomUUID())
                .accountId(account.getAccountId())
                .strategyDefinitionId(def.getStrategyDefinitionId())
                .strategyCode(def.getStrategyCode())
                .symbol(req.getSymbol())
                .intervalName(req.getIntervalName())
                .enabled(Boolean.TRUE.equals(req.getEnabled()))
                .allowLong(req.getAllowLong())
                .allowShort(req.getAllowShort())
                .maxOpenPositions(req.getMaxOpenPositions())
                .capitalAllocationPct(req.getCapitalAllocationPct())
                .priorityOrder(req.getPriorityOrder())
                .currentStatus("STOPPED")
                .isDeleted(false)
                .deletedAt(null)
                .build();

        AccountStrategy saved = accountStrategyRepository.save(entity);
        log.info("Created account strategy id={} code={} account={}",
                saved.getAccountStrategyId(), saved.getStrategyCode(), saved.getAccountId());
        return toResponse(saved);
    }

    /**
     * Soft-deletes an account strategy. Refuses to delete if any open trades reference it.
     * Historical trade/P&L attribution remains intact because the row is kept.
     */
    @Transactional
    public void softDeleteStrategy(UUID userId, UUID accountStrategyId) {
        AccountStrategy strategy = accountStrategyRepository.findById(accountStrategyId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Account strategy not found: " + accountStrategyId));

        if (Boolean.TRUE.equals(strategy.getIsDeleted())) {
            throw new EntityNotFoundException("Account strategy not found: " + accountStrategyId);
        }

        Account account = accountRepository.findByAccountId(strategy.getAccountId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Account strategy not found: " + accountStrategyId));

        if (!userId.equals(account.getUserId())) {
            throw new EntityNotFoundException("Account strategy not found: " + accountStrategyId);
        }

        long openTrades = tradesRepository.countOpenByAccountStrategyId(accountStrategyId);
        if (openTrades > 0) {
            throw new IllegalStateException(
                    "Cannot delete strategy with " + openTrades + " open trade(s). Close positions first.");
        }

        strategy.setIsDeleted(true);
        strategy.setDeletedAt(LocalDateTime.now());
        strategy.setEnabled(false);
        accountStrategyRepository.save(strategy);

        log.info("Soft-deleted account strategy id={} by userId={}", accountStrategyId, userId);
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
