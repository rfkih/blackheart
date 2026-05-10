package id.co.blackheart.service.strategy;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.StrategyParam;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.StrategyParamRepository;
import id.co.blackheart.service.audit.AuditService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * V54 — clones a (typically PUBLIC, research-agent-owned) account_strategy
 * row into a target account owned by the calling user, copying the active
 * strategy_param preset alongside it. The clone is forced into a safe-by-
 * default shape (PRIVATE / disabled / simulated / STOPPED) so the user must
 * explicitly enable it before any capital — paper or real — is at risk.
 *
 * <p>Separated from {@link AccountStrategyService} so the read/write
 * distinction stays sharp: this class is the only place that takes a
 * non-owned source row and writes a derived row into the caller's account.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountStrategyCloneService {

    private final AccountStrategyRepository accountStrategyRepository;
    private final AccountRepository accountRepository;
    private final StrategyParamRepository strategyParamRepository;
    private final StrategyParamService strategyParamService;
    private final AccountStrategyOwnershipGuard ownershipGuard;
    private final AuditService auditService;

    /**
     * @param userId            the calling user (from JWT)
     * @param sourceId          the account_strategy_id to clone — may be PUBLIC
     *                          and owned by any user, or PRIVATE and owned by
     *                          the caller
     * @param targetAccountId   optional — defaults to the caller's first account
     *                          (oldest by created_time) when null
     * @param createdBy         human label for audit / created_by
     * @return the newly created account_strategy_id
     */
    @Transactional
    public UUID clone(UUID userId, UUID sourceId, UUID targetAccountId, String createdBy) {
        AccountStrategy source = ownershipGuard.assertReadable(userId, sourceId);

        Account target = resolveTargetAccount(userId, targetAccountId);

        // Same-tuple guard. The unique constraint on
        // (account_id, strategy_definition_id, symbol, interval_name) is NOT
        // partial — soft-deleted rows still occupy the slot. So we look up
        // *including* soft-deleted and branch on which case we hit:
        //   - active duplicate  → 409, "delete the existing one"
        //   - soft-deleted only → 409, "previously deleted, restore it"
        // Without the soft-deleted branch, the INSERT would trip a
        // DataIntegrityViolation and the user gets a generic 500.
        accountStrategyRepository
                .findByUniqueKeyIncludingDeleted(target.getAccountId(), source.getStrategyDefinitionId(),
                        source.getSymbol(), source.getIntervalName())
                .ifPresent(existing -> {
                    if (Boolean.TRUE.equals(existing.getIsDeleted())) {
                        throw new IllegalStateException(
                                "You previously had a strategy for "
                                        + source.getStrategyCode() + " "
                                        + source.getSymbol() + " "
                                        + source.getIntervalName()
                                        + " on this account and deleted it. Restore the existing row "
                                        + "instead of cloning a fresh one.");
                    }
                    throw new IllegalStateException(
                            "You already have a strategy for "
                                    + source.getStrategyCode() + " "
                                    + source.getSymbol() + " "
                                    + source.getIntervalName()
                                    + " on this account. Delete the existing one before cloning.");
                });

        Integer maxPriority = accountStrategyRepository.findMaxPriorityOrderByAccountId(target.getAccountId());
        int newPriority = (maxPriority == null ? 0 : maxPriority) + 1;

        AccountStrategy clone = AccountStrategy.builder()
                .accountStrategyId(UUID.randomUUID())
                .accountId(target.getAccountId())
                .strategyDefinitionId(source.getStrategyDefinitionId())
                .strategyCode(source.getStrategyCode())
                .presetName(derivePresetName(source))
                .symbol(source.getSymbol())
                .intervalName(source.getIntervalName())
                .enabled(false)
                .simulated(true)
                .allowLong(source.getAllowLong())
                .allowShort(source.getAllowShort())
                .maxOpenPositions(source.getMaxOpenPositions())
                .capitalAllocationPct(source.getCapitalAllocationPct())
                .priorityOrder(newPriority)
                .currentStatus("STOPPED")
                .isDeleted(false)
                .ddKillThresholdPct(source.getDdKillThresholdPct())
                .isKillSwitchTripped(false)
                .regimeGateEnabled(source.getRegimeGateEnabled())
                .allowedTrendRegimes(source.getAllowedTrendRegimes())
                .allowedVolatilityRegimes(source.getAllowedVolatilityRegimes())
                .kellySizingEnabled(source.getKellySizingEnabled())
                .kellyMaxFraction(source.getKellyMaxFraction())
                .visibility("PRIVATE")
                .build();
        clone.setCreatedBy(createdBy);
        clone.setUpdatedBy(createdBy);
        AccountStrategy saved = accountStrategyRepository.save(clone);

        // Copy the source's active preset (if any) into a new active preset on
        // the clone. If the source has no active preset, seed a "default" empty
        // overrides preset so the clone can resolve params on the live path —
        // matching the AccountStrategyService.createStrategy contract.
        copyActivePreset(source.getAccountStrategyId(), saved.getAccountStrategyId(), createdBy);

        log.info("Cloned account_strategy source={} -> clone={} targetAccount={} userId={}",
                source.getAccountStrategyId(), saved.getAccountStrategyId(),
                target.getAccountId(), userId);
        // Redact the audit "before" snapshot to the minimum needed for
        // forensics. Persisting the full source entity would record the
        // foreign account's accountId/visibility/kelly/etc — useful only
        // for cross-tenant auditing, not for the user's own audit log.
        auditService.recordEvent(userId, "STRATEGY_CLONED", "AccountStrategy",
                saved.getAccountStrategyId(),
                cloneSourceSnapshot(source),
                saved);
        return saved.getAccountStrategyId();
    }

    /** Minimal, non-leaking snapshot of the clone source — just enough to
     *  recreate "what did the user clone from" without recording the foreign
     *  account_id or any tenant-specific config. */
    private record CloneSourceSnapshot(
            UUID sourceAccountStrategyId,
            String sourceStrategyCode,
            String sourceSymbol,
            String sourceIntervalName,
            String sourcePresetName,
            String sourceVisibility
    ) {}

    private CloneSourceSnapshot cloneSourceSnapshot(AccountStrategy source) {
        return new CloneSourceSnapshot(
                source.getAccountStrategyId(),
                source.getStrategyCode(),
                source.getSymbol(),
                source.getIntervalName(),
                source.getPresetName(),
                source.getVisibility());
    }

    private Account resolveTargetAccount(UUID userId, UUID targetAccountId) {
        if (targetAccountId != null) {
            Account account = accountRepository.findByAccountId(targetAccountId)
                    .orElseThrow(() -> new EntityNotFoundException("Account not found: " + targetAccountId));
            if (!userId.equals(account.getUserId())) {
                throw new EntityNotFoundException("Account not found: " + targetAccountId);
            }
            return account;
        }
        List<Account> accounts = accountRepository.findByUserId(userId);
        if (accounts.isEmpty()) {
            throw new IllegalStateException(
                    "You have no accounts. Create an account before cloning a strategy.");
        }
        return accounts.get(0);
    }

    private String derivePresetName(AccountStrategy source) {
        String base = source.getPresetName();
        if (base == null || base.isBlank()) {
            return "Cloned from research-agent";
        }
        return base + " (cloned)";
    }

    private void copyActivePreset(UUID sourceAccountStrategyId, UUID targetAccountStrategyId, String createdBy) {
        StrategyParam src = strategyParamRepository.findActiveByAccountStrategyId(sourceAccountStrategyId).orElse(null);
        if (src == null) {
            strategyParamService.create(
                    targetAccountStrategyId,
                    "default",
                    new HashMap<>(),
                    /*activate=*/true,
                    /*sourceBacktestRunId=*/null,
                    createdBy);
            return;
        }
        // Deliberately drop source_backtest_run_id — the source run belongs
        // to the original owner (typically the research-agent), and the
        // calling user has no read access to it (BacktestQueryService scopes
        // by JWT userId). Carrying the FK forward would surface a UUID the
        // user can't dereference and produce dead "view source backtest"
        // deep links in the UI. The param values are what matters; the
        // provenance lives in the audit log instead.
        strategyParamService.create(
                targetAccountStrategyId,
                src.getName(),
                src.getParamOverrides(),
                /*activate=*/true,
                /*sourceBacktestRunId=*/null,
                createdBy);
    }
}
