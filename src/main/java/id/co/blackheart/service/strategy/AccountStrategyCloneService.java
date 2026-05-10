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
import org.springframework.util.StringUtils;

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

        // Same-tuple lookup. The unique constraint on
        // (account_id, strategy_definition_id, symbol, interval_name) is NOT
        // partial — soft-deleted rows still occupy the slot. Branch on which
        // case we hit:
        //   - active duplicate  → 409, the user must delete the existing one
        //                          first (we don't silently overwrite a row
        //                          that may be running / have open trades).
        //   - soft-deleted only → seamless re-clone path: revive the row in
        //                          place, overwrite its config from the
        //                          source, and replace the active preset.
        //                          Preserves the original account_strategy_id
        //                          so historical trade attribution stays
        //                          intact, and the user sees a normal
        //                          successful clone instead of an error.
        AccountStrategy existing = accountStrategyRepository
                .findByUniqueKeyIncludingDeleted(target.getAccountId(), source.getStrategyDefinitionId(),
                        source.getSymbol(), source.getIntervalName())
                .orElse(null);

        if (existing != null && !Boolean.TRUE.equals(existing.getIsDeleted())) {
            throw new IllegalStateException(
                    "You already have a strategy for "
                            + source.getStrategyCode() + " "
                            + source.getSymbol() + " "
                            + source.getIntervalName()
                            + " on this account. Delete the existing one before cloning.");
        }
        if (existing != null) {
            return reviveAndRecloneInto(existing, source, target, createdBy, userId);
        }

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
                .useRiskBasedSizing(source.getUseRiskBasedSizing())
                .riskPct(source.getRiskPct())
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

    /**
     * Seamless re-clone: the destination tuple already has a soft-deleted row,
     * so revive it in place rather than failing with a "previously deleted"
     * conflict. Preserves the original {@code account_strategy_id} (so any
     * historical trades / paper-trade runs / audit rows linked to it stay
     * resolvable), but overwrites the row's mutable config from the source
     * and replaces the active strategy_param preset. Resets the row to the
     * same safe-by-default shape a fresh clone would land in: PRIVATE,
     * disabled, simulated, STOPPED, kill-switch cleared, priority pushed to
     * the bottom of the user's list.
     */
    private UUID reviveAndRecloneInto(AccountStrategy revived, AccountStrategy source,
                                      Account target, String createdBy, UUID userId) {
        RevivedSnapshot beforeSnapshot = snapshotForAudit(revived);

        Integer maxPriority = accountStrategyRepository.findMaxPriorityOrderByAccountId(target.getAccountId());
        int newPriority = (maxPriority == null ? 0 : maxPriority) + 1;

        revived.setIsDeleted(false);
        revived.setDeletedAt(null);
        revived.setStrategyCode(source.getStrategyCode());
        revived.setPresetName(derivePresetName(source));
        revived.setEnabled(false);
        revived.setSimulated(true);
        revived.setAllowLong(source.getAllowLong());
        revived.setAllowShort(source.getAllowShort());
        revived.setMaxOpenPositions(source.getMaxOpenPositions());
        revived.setCapitalAllocationPct(source.getCapitalAllocationPct());
        revived.setPriorityOrder(newPriority);
        revived.setCurrentStatus("STOPPED");
        revived.setDdKillThresholdPct(source.getDdKillThresholdPct());
        revived.setIsKillSwitchTripped(false);
        revived.setKillSwitchTrippedAt(null);
        revived.setKillSwitchReason(null);
        revived.setRegimeGateEnabled(source.getRegimeGateEnabled());
        revived.setAllowedTrendRegimes(source.getAllowedTrendRegimes());
        revived.setAllowedVolatilityRegimes(source.getAllowedVolatilityRegimes());
        revived.setKellySizingEnabled(source.getKellySizingEnabled());
        revived.setKellyMaxFraction(source.getKellyMaxFraction());
        revived.setUseRiskBasedSizing(source.getUseRiskBasedSizing());
        revived.setRiskPct(source.getRiskPct());
        revived.setVisibility("PRIVATE");
        revived.setUpdatedBy(createdBy);
        AccountStrategy saved = accountStrategyRepository.save(revived);

        // Replace the active preset with the source's. StrategyParamService.create
        // deactivates any prior active preset on the same account_strategy_id
        // before flagging the new row as active, so the revived row's stale
        // active preset (from before deletion) is naturally retired.
        copyActivePreset(source.getAccountStrategyId(), saved.getAccountStrategyId(), createdBy);

        log.info("Revived + re-cloned account_strategy id={} from source={} targetAccount={} userId={}",
                saved.getAccountStrategyId(), source.getAccountStrategyId(),
                target.getAccountId(), userId);
        auditService.recordEvent(userId, "STRATEGY_RESTORED_AND_RECLONED", "AccountStrategy",
                saved.getAccountStrategyId(),
                beforeSnapshot,
                saved);
        return saved.getAccountStrategyId();
    }

    /** Minimal pre-mutation snapshot for audit. Avoids recording the
     *  full live entity reference (which Hibernate would mutate after save,
     *  giving the audit a misleading "before" payload). */
    private record RevivedSnapshot(
            UUID accountStrategyId,
            String strategyCode,
            String symbol,
            String intervalName,
            String presetName,
            Boolean isDeleted,
            String currentStatus,
            Integer priorityOrder
    ) {}

    private RevivedSnapshot snapshotForAudit(AccountStrategy s) {
        return new RevivedSnapshot(
                s.getAccountStrategyId(),
                s.getStrategyCode(),
                s.getSymbol(),
                s.getIntervalName(),
                s.getPresetName(),
                s.getIsDeleted(),
                s.getCurrentStatus(),
                s.getPriorityOrder());
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
        if (!StringUtils.hasText(base)) {
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
