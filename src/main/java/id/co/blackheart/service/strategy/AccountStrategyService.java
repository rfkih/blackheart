package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.request.CreateAccountStrategyRequest;
import id.co.blackheart.dto.request.UpdateAccountStrategyRequest;
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
import java.util.Optional;
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
        AccountStrategy strategy = loadOwnedActive(userId, accountStrategyId);
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
     * Creates a new preset row for the given tuple. Multiple presets may
     * coexist — only one preset per (account, strategy-definition, symbol,
     * interval) can be {@code enabled=true} at a time, which the partial
     * unique index {@code uq_account_strategy_active_preset} enforces at the
     * DB level.
     *
     * <ul>
     *   <li>If the incoming preset name clashes with a sibling's name (and
     *       that sibling is not soft-deleted), reject with 409.</li>
     *   <li>If {@code enabled=true} was requested AND an active sibling
     *       exists, deactivate the sibling first so the new row can take its
     *       place atomically within the transaction.</li>
     * </ul>
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

        List<AccountStrategy> siblings = accountStrategyRepository.findPresetsForTuple(
                account.getAccountId(),
                def.getStrategyDefinitionId(),
                req.getSymbol(),
                req.getIntervalName());

        String presetName = resolvePresetName(req.getPresetName(), siblings);

        // Reject duplicate labels within the same tuple — otherwise users get
        // two "aggressive" rows and can't tell them apart in the preset picker.
        for (AccountStrategy sibling : siblings) {
            if (presetName.equalsIgnoreCase(sibling.getPresetName())) {
                throw new IllegalStateException(
                        "A preset named \"" + presetName + "\" already exists for this strategy. "
                                + "Pick a different name.");
            }
        }

        boolean shouldActivate = Boolean.TRUE.equals(req.getEnabled());
        if (shouldActivate) {
            deactivateActiveSibling(
                    account.getAccountId(),
                    def.getStrategyDefinitionId(),
                    req.getSymbol(),
                    req.getIntervalName());
        }

        AccountStrategy entity = AccountStrategy.builder()
                .accountStrategyId(UUID.randomUUID())
                .accountId(account.getAccountId())
                .strategyDefinitionId(def.getStrategyDefinitionId())
                .strategyCode(def.getStrategyCode())
                .presetName(presetName)
                .symbol(req.getSymbol())
                .intervalName(req.getIntervalName())
                .enabled(shouldActivate)
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
        log.info("Created account strategy id={} code={} preset={} account={} enabled={}",
                saved.getAccountStrategyId(), saved.getStrategyCode(),
                saved.getPresetName(), saved.getAccountId(), saved.getEnabled());
        return toResponse(saved);
    }

    /**
     * Activates the given preset for its (account, strategy-definition, symbol,
     * interval) tuple. If a different preset is currently enabled for the same
     * tuple it is deactivated first, inside the same transaction, so the
     * partial unique index never sees two active rows simultaneously.
     *
     * <p>Refuses to activate if the TARGET is soft-deleted or if any existing
     * active preset still has open trades — switching presets mid-trade is
     * explicitly not allowed (different params mid-position → unsafe risk
     * management).
     */
    @Transactional
    public AccountStrategyResponse activateStrategy(UUID userId, UUID accountStrategyId) {
        AccountStrategy target = loadOwnedActive(userId, accountStrategyId);

        if (Boolean.TRUE.equals(target.getEnabled())) {
            // Idempotent — already active.
            return toResponse(target);
        }

        Optional<AccountStrategy> currentActive = accountStrategyRepository.findActivePreset(
                target.getAccountId(),
                target.getStrategyDefinitionId(),
                target.getSymbol(),
                target.getIntervalName());

        if (currentActive.isPresent()) {
            AccountStrategy active = currentActive.get();
            long openTrades = tradesRepository.countOpenByAccountStrategyId(active.getAccountStrategyId());
            if (openTrades > 0) {
                throw new IllegalStateException(
                        "Current preset \"" + active.getPresetName() + "\" has "
                                + openTrades + " open trade(s). Close positions before switching presets.");
            }
            active.setEnabled(false);
            accountStrategyRepository.save(active);
            // Flush so the partial unique index sees the deactivation before
            // we enable the new preset within the same transaction.
            accountStrategyRepository.flush();
        }

        target.setEnabled(true);
        AccountStrategy saved = accountStrategyRepository.save(target);

        log.info("Activated preset id={} name={} (deactivated previous active={})",
                saved.getAccountStrategyId(), saved.getPresetName(),
                currentActive.map(AccountStrategy::getAccountStrategyId).orElse(null));

        return toResponse(saved);
    }

    /**
     * Deactivates the given preset — flips {@code enabled=false} so the live
     * orchestrator stops evaluating it for new entries. Does NOT touch open
     * positions on this strategy: the live listener continues managing them
     * (stop-loss, trailing stop, take-profit) until they close naturally.
     *
     * <p>Idempotent — calling on an already-stopped row is a no-op.
     */
    @Transactional
    public AccountStrategyResponse deactivateStrategy(UUID userId, UUID accountStrategyId) {
        AccountStrategy strategy = loadOwnedActive(userId, accountStrategyId);

        if (!Boolean.TRUE.equals(strategy.getEnabled())) {
            return toResponse(strategy);
        }

        strategy.setEnabled(false);
        AccountStrategy saved = accountStrategyRepository.save(strategy);

        log.info("Deactivated preset id={} name={}",
                saved.getAccountStrategyId(), saved.getPresetName());

        return toResponse(saved);
    }

    /**
     * Clear the drawdown kill-switch trip state. Caller is expected to have
     * already looked at why it tripped (the reason is on the row + in logs).
     * Doesn't auto-clear because the trip itself is the "human, look at this"
     * signal — letting it self-clear on the next winning trade defeats the
     * point. Idempotent on a not-tripped row.
     */
    @Transactional
    public AccountStrategyResponse rearmKillSwitch(UUID userId, UUID accountStrategyId) {
        AccountStrategy strategy = loadOwnedActive(userId, accountStrategyId);
        if (!Boolean.TRUE.equals(strategy.getIsKillSwitchTripped())) {
            return toResponse(strategy);
        }
        strategy.setIsKillSwitchTripped(Boolean.FALSE);
        strategy.setKillSwitchTrippedAt(null);
        strategy.setKillSwitchReason(null);
        AccountStrategy saved = accountStrategyRepository.save(strategy);
        log.info("Re-armed kill switch | userId={} accountStrategyId={}",
                userId, accountStrategyId);
        return toResponse(saved);
    }

    /**
     * Partial update — currently only the candle interval. Refuses if the
     * strategy has any open trades (those were sized / stopped on the OLD
     * interval; switching candle granularity mid-position is unsafe). When
     * the strategy is currently {@code enabled=true} and the new interval
     * tuple already has a different active sibling, that sibling is
     * deactivated atomically (rejecting if the sibling has open trades),
     * mirroring the activate flow.
     */
    @Transactional
    public AccountStrategyResponse updateStrategy(
            UUID userId, UUID accountStrategyId, UpdateAccountStrategyRequest req) {
        AccountStrategy strategy = loadOwnedActive(userId, accountStrategyId);

        String newInterval = req.getIntervalName().trim();
        if (newInterval.equalsIgnoreCase(strategy.getIntervalName())) {
            // Idempotent — no change.
            return toResponse(strategy);
        }

        long openTrades = tradesRepository.countOpenByAccountStrategyId(accountStrategyId);
        if (openTrades > 0) {
            throw new IllegalStateException(
                    "Cannot change interval — strategy has " + openTrades
                            + " open trade(s). Close positions first.");
        }

        if (Boolean.TRUE.equals(strategy.getEnabled())) {
            // Moving an active preset into a new tuple: the new tuple may
            // already have an active sibling that we'd collide with at the
            // partial-unique-index level. Deactivate it atomically here.
            deactivateActiveSibling(
                    strategy.getAccountId(),
                    strategy.getStrategyDefinitionId(),
                    strategy.getSymbol(),
                    newInterval);
        }

        strategy.setIntervalName(newInterval);
        AccountStrategy saved = accountStrategyRepository.save(strategy);

        log.info("Updated strategy id={} interval -> {}", saved.getAccountStrategyId(), newInterval);

        return toResponse(saved);
    }

    /**
     * Soft-deletes an account strategy. Refuses to delete if any open trades reference it.
     * Historical trade/P&L attribution remains intact because the row is kept.
     */
    @Transactional
    public void softDeleteStrategy(UUID userId, UUID accountStrategyId) {
        AccountStrategy strategy = loadOwnedActive(userId, accountStrategyId);

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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AccountStrategy loadOwnedActive(UUID userId, UUID accountStrategyId) {
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

    private void deactivateActiveSibling(
            UUID accountId, UUID strategyDefinitionId, String symbol, String intervalName) {
        Optional<AccountStrategy> active = accountStrategyRepository.findActivePreset(
                accountId, strategyDefinitionId, symbol, intervalName);
        if (active.isEmpty()) return;

        AccountStrategy row = active.get();
        long openTrades = tradesRepository.countOpenByAccountStrategyId(row.getAccountStrategyId());
        if (openTrades > 0) {
            throw new IllegalStateException(
                    "Current preset \"" + row.getPresetName() + "\" has "
                            + openTrades + " open trade(s). Close positions before creating "
                            + "a new active preset for the same strategy.");
        }
        row.setEnabled(false);
        accountStrategyRepository.save(row);
        accountStrategyRepository.flush();
    }

    /**
     * If the client didn't supply a preset name, synthesise one that doesn't
     * collide with siblings. "Preset 1", "Preset 2", … — easy to rename later.
     */
    private String resolvePresetName(String requested, List<AccountStrategy> siblings) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        int next = siblings.size() + 1;
        while (next < 10_000) {
            String candidate = "Preset " + next;
            final String c = candidate;
            boolean taken = siblings.stream()
                    .anyMatch(s -> c.equalsIgnoreCase(s.getPresetName()));
            if (!taken) return candidate;
            next++;
        }
        return "Preset " + UUID.randomUUID().toString().substring(0, 6);
    }

    private AccountStrategyResponse toResponse(AccountStrategy s) {
        return AccountStrategyResponse.builder()
                .accountStrategyId(s.getAccountStrategyId())
                .accountId(s.getAccountId())
                .strategyDefinitionId(s.getStrategyDefinitionId())
                .strategyCode(s.getStrategyCode())
                .presetName(s.getPresetName())
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
                .ddKillThresholdPct(s.getDdKillThresholdPct())
                .isKillSwitchTripped(s.getIsKillSwitchTripped())
                .killSwitchTrippedAt(s.getKillSwitchTrippedAt())
                .killSwitchReason(s.getKillSwitchReason())
                .build();
    }
}
