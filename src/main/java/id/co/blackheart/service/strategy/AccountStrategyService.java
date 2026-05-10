package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.request.CreateAccountStrategyRequest;
import id.co.blackheart.dto.request.UpdateAccountStrategyRequest;
import id.co.blackheart.dto.response.AccountStrategyResponse;
import id.co.blackheart.engine.EngineMetrics;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.StrategyDefinition;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.StrategyDefinitionRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.alert.AlertService;
import id.co.blackheart.service.alert.AlertSeverity;
import id.co.blackheart.service.audit.AuditService;
import id.co.blackheart.service.risk.KellySizingService;
import id.co.blackheart.util.AppConstant;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountStrategyService {

    private final AccountRepository accountRepository;
    private final AccountStrategyRepository accountStrategyRepository;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final TradesRepository tradesRepository;
    private final AuditService auditService;
    private final EngineMetrics engineMetrics;
    private final StrategyParamService strategyParamService;
    private final AlertService alertService;
    private final KellySizingService kellySizingService;

    /**
     * V54 — pinned UUID of the dedicated research-agent user (Flyway V54).
     * Strategies created on accounts owned by this user default to
     * visibility=PUBLIC so the autonomous loop's catalogue is browsable
     * platform-wide without operators having to remember the flag.
     * Overridable in tests via {@code blackheart.research-agent.user-id}.
     */
    @Value("${blackheart.research-agent.user-id:99999999-9999-9999-9999-000000000001}")
    private String researchAgentUserId;

    /**
     * V54 — returns every strategy visible to the user: their own (any
     * visibility) plus every PUBLIC strategy from other accounts. Each row
     * is decorated with {@code ownedByCurrentUser} and {@code ownerLabel} so
     * the frontend can render "edit/delete" vs "clone" affordances without a
     * second round-trip and without leaking foreign userIds.
     */
    @Transactional(readOnly = true)
    public List<AccountStrategyResponse> getStrategiesByUser(UUID userId) {
        List<AccountStrategy> visible = accountStrategyRepository.findAllVisibleToUser(userId);
        if (visible.isEmpty()) {
            return List.of();
        }
        Map<UUID, Account> ownerByAccountId = loadOwnerAccounts(visible);
        return visible.stream()
                .map(s -> toResponse(s, userId, ownerByAccountId.get(s.getAccountId())))
                .toList();
    }

    private Map<UUID, Account> loadOwnerAccounts(List<AccountStrategy> rows) {
        Set<UUID> accountIds = rows.stream()
                .map(AccountStrategy::getAccountId)
                .collect(Collectors.toSet());
        Map<UUID, Account> result = HashMap.newHashMap(accountIds.size());
        for (UUID id : accountIds) {
            accountRepository.findByAccountId(id).ifPresent(a -> result.put(id, a));
        }
        return result;
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
     * Returns the live Kelly status for a strategy: enabled flag, current
     * effective multiplier, configured cap, qualifying-run count, and a
     * human-readable reason. Used by the strategy detail page to surface
     * what Kelly is actually doing right now (which is otherwise only
     * visible in JVM logs).
     */
    @Transactional(readOnly = true)
    public KellySizingService.KellyStatus getKellyStatus(UUID userId, UUID accountStrategyId) {
        // Tenant scope check — same pattern as the other read endpoints.
        loadOwnedActive(userId, accountStrategyId);
        return kellySizingService.getStatus(accountStrategyId);
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

        // V15+ promotion-pipeline default: every new strategy starts in
        // simulated=true so the safe path (paper-trade quarantine) is the
        // default path. To go live, an admin must explicitly transition
        // via POST /api/v1/strategy-promotion/{id}/promote with reason +
        // evidence — the audit trail of "we considered this and approved
        // it" is then recorded. Direct UPDATE on simulated still works
        // for emergency operations but bypasses the workflow. (Bug 5 fix,
        // 2026-04-28.)
        // V54 — research-agent-owned strategies default to PUBLIC so the
        // autonomous loop's catalogue is browsable to all tenants without
        // manual flag-flipping. Everyone else defaults PRIVATE (entity
        // builder default).
        String visibility = isResearchAgentAccount(account) ? "PUBLIC" : "PRIVATE";

        // V55 — new presets default to risk-based sizing ON so the platform
        // pushes operators onto the unified risk model. The user can flip the
        // toggle off explicitly via the request, and existing rows (created
        // pre-V55) keep the migration's FALSE default.
        Boolean useRiskSizing = req.getUseRiskBasedSizing() != null
                ? req.getUseRiskBasedSizing()
                : Boolean.TRUE;
        BigDecimal riskPct = req.getRiskPct() != null
                ? req.getRiskPct()
                : new BigDecimal("0.0500");

        AccountStrategy entity = AccountStrategy.builder()
                .accountStrategyId(UUID.randomUUID())
                .accountId(account.getAccountId())
                .strategyDefinitionId(def.getStrategyDefinitionId())
                .strategyCode(def.getStrategyCode())
                .presetName(presetName)
                .symbol(req.getSymbol())
                .intervalName(req.getIntervalName())
                .enabled(shouldActivate)
                .simulated(Boolean.TRUE)
                .allowLong(req.getAllowLong())
                .allowShort(req.getAllowShort())
                .maxOpenPositions(req.getMaxOpenPositions())
                .capitalAllocationPct(req.getCapitalAllocationPct())
                .priorityOrder(req.getPriorityOrder())
                .currentStatus("STOPPED")
                .isDeleted(false)
                .deletedAt(null)
                .visibility(visibility)
                .useRiskBasedSizing(useRiskSizing)
                .riskPct(riskPct)
                .build();

        AccountStrategy saved = accountStrategyRepository.save(entity);

        // Seed a default active strategy_param preset so the new strategy can
        // resolve params on the live path immediately. Without this, a freshly
        // created (and later promoted) strategy would have no active preset and
        // every read would fall through to archetype defaults — which is fine
        // operationally, but the user-stated invariant is "every promoted
        // strategy has a default parameter row". The empty {} overrides map
        // means "use defaults verbatim"; the operator can then create
        // additional named presets via the saved-preset API.
        strategyParamService.create(
                saved.getAccountStrategyId(),
                "default",
                java.util.Map.of(),
                /*activate=*/true,
                /*sourceBacktestRunId=*/null,
                "system");

        log.info("Created account strategy id={} code={} preset={} account={} enabled={}",
                saved.getAccountStrategyId(), saved.getStrategyCode(),
                saved.getPresetName(), saved.getAccountId(), saved.getEnabled());
        auditService.recordEvent(userId, "STRATEGY_CREATED", AppConstant.ENTITY_ACCOUNT_STRATEGY,
                saved.getAccountStrategyId(), null, saved);
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

        auditService.recordEvent(userId, "STRATEGY_ACTIVATED", AppConstant.ENTITY_ACCOUNT_STRATEGY,
                saved.getAccountStrategyId(), null, saved);
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

        auditService.recordEvent(userId, "STRATEGY_DEACTIVATED", AppConstant.ENTITY_ACCOUNT_STRATEGY,
                saved.getAccountStrategyId(), null, saved);
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
        // Reset the in-memory error window AFTER the rearm row commits.
        // Resetting in-tx would clear the window even if the tx rolled back,
        // leaving the rearm-flag still tripped but the error history wiped.
        UUID toReset = saved.getAccountStrategyId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    engineMetrics.reset(toReset);
                }
            });
        } else {
            engineMetrics.reset(toReset);
        }
        log.info("Re-armed kill switch | userId={} accountStrategyId={}",
                userId, accountStrategyId);
        auditService.recordEvent(userId, "KILL_SWITCH_REARMED", AppConstant.ENTITY_ACCOUNT_STRATEGY,
                saved.getAccountStrategyId(), null, saved);
        alertService.raise(
                AlertSeverity.INFO,
                "KILL_SWITCH_REARMED",
                String.format("Kill-switch re-armed on %s (%s) by user %s",
                        saved.getStrategyCode() != null ? saved.getStrategyCode() : "?",
                        saved.getAccountStrategyId(), userId));
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

        // Snapshot the relevant editable fields BEFORE mutating so the audit
        // shows what actually changed. Cloning the whole entity here would
        // also work; this is lighter and matches the verbs we audit.
        AccountStrategySnapshot before = AccountStrategySnapshot.of(strategy);

        boolean dirty = false;
        dirty |= applyIntervalChange(strategy, req, accountStrategyId);
        dirty |= applyPriorityOrderChange(strategy, req);
        dirty |= applyRegimeGateChange(strategy, req);
        dirty |= applyKellyChange(strategy, req);
        dirty |= applyRiskSizingChange(strategy, req);

        if (!dirty) {
            return toResponse(strategy);
        }
        AccountStrategy saved = accountStrategyRepository.save(strategy);
        auditService.recordEvent(userId, "STRATEGY_UPDATED", AppConstant.ENTITY_ACCOUNT_STRATEGY,
                saved.getAccountStrategyId(), before, AccountStrategySnapshot.of(saved));
        return toResponse(saved);
    }

    private boolean applyIntervalChange(
            AccountStrategy strategy, UpdateAccountStrategyRequest req, UUID accountStrategyId) {
        if (!StringUtils.hasText(req.getIntervalName())) return false;
        String newInterval = req.getIntervalName().trim();
        if (newInterval.equalsIgnoreCase(strategy.getIntervalName())) return false;

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
        log.info("Updated strategy id={} interval -> {}", strategy.getAccountStrategyId(), newInterval);
        return true;
    }

    // No open-trade guard needed: priority only affects future entry
    // fan-out, not in-flight position management.
    private boolean applyPriorityOrderChange(AccountStrategy strategy, UpdateAccountStrategyRequest req) {
        if (req.getPriorityOrder() == null
                || req.getPriorityOrder().equals(strategy.getPriorityOrder())) {
            return false;
        }
        strategy.setPriorityOrder(req.getPriorityOrder());
        log.info("Updated strategy id={} priorityOrder -> {}",
                strategy.getAccountStrategyId(), req.getPriorityOrder());
        return true;
    }

    // V43 — regime gate fields.
    private boolean applyRegimeGateChange(AccountStrategy strategy, UpdateAccountStrategyRequest req) {
        boolean dirty = false;
        if (req.getRegimeGateEnabled() != null
                && !req.getRegimeGateEnabled().equals(strategy.getRegimeGateEnabled())) {
            strategy.setRegimeGateEnabled(req.getRegimeGateEnabled());
            dirty = true;
            log.info("Updated strategy id={} regimeGateEnabled -> {}",
                    strategy.getAccountStrategyId(), req.getRegimeGateEnabled());
        }
        if (req.getAllowedTrendRegimes() != null) {
            strategy.setAllowedTrendRegimes(normalizeRegimeCsv(req.getAllowedTrendRegimes()));
            dirty = true;
        }
        if (req.getAllowedVolatilityRegimes() != null) {
            strategy.setAllowedVolatilityRegimes(normalizeRegimeCsv(req.getAllowedVolatilityRegimes()));
            dirty = true;
        }
        return dirty;
    }

    private static String normalizeRegimeCsv(String raw) {
        return !StringUtils.hasText(raw) ? null : raw.trim().toUpperCase();
    }

    // V45 — Kelly / bankroll sizing fields.
    private boolean applyKellyChange(AccountStrategy strategy, UpdateAccountStrategyRequest req) {
        boolean dirty = false;
        if (req.getKellySizingEnabled() != null
                && !req.getKellySizingEnabled().equals(strategy.getKellySizingEnabled())) {
            strategy.setKellySizingEnabled(req.getKellySizingEnabled());
            dirty = true;
            log.info("Updated strategy id={} kellySizingEnabled -> {}",
                    strategy.getAccountStrategyId(), req.getKellySizingEnabled());
        }
        if (req.getKellyMaxFraction() != null
                && req.getKellyMaxFraction().compareTo(strategy.getKellyMaxFraction()) != 0) {
            strategy.setKellyMaxFraction(req.getKellyMaxFraction());
            dirty = true;
            log.info("Updated strategy id={} kellyMaxFraction -> {}",
                    strategy.getAccountStrategyId(), req.getKellyMaxFraction());
        }
        return dirty;
    }

    // V55 — risk-based sizing toggle + per-trade risk fraction.
    private boolean applyRiskSizingChange(AccountStrategy strategy, UpdateAccountStrategyRequest req) {
        boolean dirty = false;
        if (req.getUseRiskBasedSizing() != null
                && !req.getUseRiskBasedSizing().equals(strategy.getUseRiskBasedSizing())) {
            strategy.setUseRiskBasedSizing(req.getUseRiskBasedSizing());
            dirty = true;
            log.info("Updated strategy id={} useRiskBasedSizing -> {}",
                    strategy.getAccountStrategyId(), req.getUseRiskBasedSizing());
        }
        if (req.getRiskPct() != null
                && req.getRiskPct().compareTo(strategy.getRiskPct()) != 0) {
            strategy.setRiskPct(req.getRiskPct());
            dirty = true;
            log.info("Updated strategy id={} riskPct -> {}",
                    strategy.getAccountStrategyId(), req.getRiskPct());
        }
        return dirty;
    }

    /**
     * Lightweight before/after snapshot of the editable fields. Avoids
     * dragging the full entity (including non-serializable JPA lazy refs)
     * into the audit JSON.
     */
    private record AccountStrategySnapshot(
            String intervalName,
            Integer priorityOrder,
            Boolean enabled,
            Boolean regimeGateEnabled,
            String allowedTrendRegimes,
            String allowedVolatilityRegimes,
            Boolean kellySizingEnabled,
            BigDecimal kellyMaxFraction,
            Boolean useRiskBasedSizing,
            BigDecimal riskPct
    ) {
        static AccountStrategySnapshot of(AccountStrategy s) {
            return new AccountStrategySnapshot(
                    s.getIntervalName(),
                    s.getPriorityOrder(),
                    s.getEnabled(),
                    s.getRegimeGateEnabled(),
                    s.getAllowedTrendRegimes(),
                    s.getAllowedVolatilityRegimes(),
                    s.getKellySizingEnabled(),
                    s.getKellyMaxFraction(),
                    s.getUseRiskBasedSizing(),
                    s.getRiskPct()
            );
        }
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

        AccountStrategySnapshot before = AccountStrategySnapshot.of(strategy);
        strategy.setIsDeleted(true);
        strategy.setDeletedAt(LocalDateTime.now());
        strategy.setEnabled(false);
        accountStrategyRepository.save(strategy);

        log.info("Soft-deleted account strategy id={} by userId={}", accountStrategyId, userId);
        auditService.recordEvent(userId, "STRATEGY_DELETED", AppConstant.ENTITY_ACCOUNT_STRATEGY,
                accountStrategyId, before, null);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * V54 — true when the account belongs to the dedicated research-agent
     * user. Used to default new strategies on that account to PUBLIC so the
     * autonomous loop's catalogue stays visible cross-tenant.
     */
    private boolean isResearchAgentAccount(Account account) {
        if (account == null || account.getUserId() == null) return false;
        try {
            UUID agentUuid = UUID.fromString(researchAgentUserId);
            return agentUuid.equals(account.getUserId());
        } catch (IllegalArgumentException e) {
            // Misconfigured property — fail closed (treat as not-agent).
            log.warn("blackheart.research-agent.user-id is not a valid UUID: {}", researchAgentUserId);
            return false;
        }
    }

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
        if (StringUtils.hasText(requested)) {
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
        // Single-row paths (read by id, mutate, return) — caller already
        // owns the row so the V54 decorations resolve trivially. Avoids a
        // second account round-trip in the hot single-row paths.
        return toResponse(s, null, null);
    }

    /**
     * V54 — full response with ownership decoration. Pass {@code currentUserId}
     * (the JWT user) and the {@code ownerAccount} for the row's account_id;
     * either may be null on legacy single-row paths where ownership is implicit.
     */
    private AccountStrategyResponse toResponse(AccountStrategy s, UUID currentUserId, Account ownerAccount) {
        boolean owned;
        String ownerLabel;
        if (ownerAccount == null) {
            owned = true;
            ownerLabel = "You";
        } else {
            owned = currentUserId != null && currentUserId.equals(ownerAccount.getUserId());
            if (owned) {
                ownerLabel = "You";
            } else if (isResearchAgentAccount(ownerAccount)) {
                // Stable, non-leaking label for the agent's catalogue.
                ownerLabel = "Research Agent";
            } else {
                // V54 — collapse all other foreign owners to a generic label
                // so accidentally-PUBLIC rows from other tenants don't leak
                // their account.username (which can carry intent like
                // "aggressive-leveraged-X"). Only the agent's catalogue
                // surfaces a recognisable label; everything else is
                // anonymised at the API boundary.
                ownerLabel = "Other Trader";
            }
        }
        return AccountStrategyResponse.builder()
                .accountStrategyId(s.getAccountStrategyId())
                .accountId(s.getAccountId())
                .strategyDefinitionId(s.getStrategyDefinitionId())
                .strategyCode(s.getStrategyCode())
                .presetName(s.getPresetName())
                .symbol(s.getSymbol())
                .intervalName(s.getIntervalName())
                .enabled(s.getEnabled())
                .simulated(s.getSimulated())
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
                .regimeGateEnabled(s.getRegimeGateEnabled())
                .allowedTrendRegimes(s.getAllowedTrendRegimes())
                .allowedVolatilityRegimes(s.getAllowedVolatilityRegimes())
                .kellySizingEnabled(s.getKellySizingEnabled())
                .kellyMaxFraction(s.getKellyMaxFraction())
                .useRiskBasedSizing(s.getUseRiskBasedSizing())
                .riskPct(s.getRiskPct())
                .visibility(s.getVisibility())
                .ownedByCurrentUser(owned)
                .ownerLabel(ownerLabel)
                .build();
    }
}
