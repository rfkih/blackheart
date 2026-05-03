package id.co.blackheart.service.risk;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.alert.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Targets the high-leverage gates the live executor depends on:
 *  - sticky trip blocks new entries until re-armed
 *  - exceeding threshold trips the switch (and persists)
 *  - concurrency cap blocks at the limit
 *  - happy path passes both gates
 */
@ExtendWith(MockitoExtension.class)
class RiskGuardServiceTest {

    @Mock private AccountStrategyRepository accountStrategyRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private TradesRepository tradesRepository;
    @Mock private FeatureStoreRepository featureStoreRepository;
    @Mock private AlertService alertService;
    @Mock private RegimeGuardService regimeGuardService;
    @Mock private CorrelationGuardService correlationGuardService;
    @InjectMocks private RiskGuardService guard;

    private UUID accountStrategyId;
    private UUID accountId;
    private AccountStrategy strategy;
    private Account account;

    @BeforeEach
    void setup() {
        accountStrategyId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        strategy = AccountStrategy.builder()
                .accountStrategyId(accountStrategyId)
                .accountId(accountId)
                .ddKillThresholdPct(new BigDecimal("25.00"))
                .isKillSwitchTripped(Boolean.FALSE)
                .build();
        account = new Account();
        account.setAccountId(accountId);
        account.setMaxConcurrentLongs(2);
        account.setMaxConcurrentShorts(2);

        // Lenient stubs for steps (4) and (5) — regime gate and correlation guard.
        // These are lenient so tests that short-circuit before these steps (e.g.
        // kill-switch-already-tripped, DD-exceeded) don't fail with "unnecessary
        // stubbing" in strict mode.
        lenient().when(featureStoreRepository.findLatestCompletedBySymbolAndInterval(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(regimeGuardService.check(any(), any()))
                .thenReturn(RegimeGuardService.RegimeVerdict.allow());
        lenient().when(correlationGuardService.check(any(), any(), any()))
                .thenReturn(CorrelationGuardService.ConcentrationVerdict.allow());
    }

    @Test
    void allowsWhenAllGatesClear() {
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(accountRepository.findByAccountId(accountId)).thenReturn(Optional.of(account));
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(List.of());
        when(tradesRepository.countOpenByAccountIdAndSide(eq(accountId), anyString())).thenReturn(0L);

        RiskGuardService.GuardVerdict verdict = guard.canOpen(accountStrategyId, "LONG");

        assertTrue(verdict.allowed());
        assertEquals(0, verdict.currentDdPct().compareTo(BigDecimal.ZERO));
        verify(accountStrategyRepository, never()).save(any());
    }

    @Test
    void deniesWhenKillSwitchAlreadyTripped() {
        strategy.setIsKillSwitchTripped(Boolean.TRUE);
        strategy.setKillSwitchReason("manual review");
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));

        RiskGuardService.GuardVerdict verdict = guard.canOpen(accountStrategyId, "LONG");

        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("already tripped"));
        // Cheap-path: no DD calc, no concurrency lookup, no save.
        verifyNoInteractions(tradesRepository);
        verify(accountStrategyRepository, never()).save(any());
    }

    @Test
    void tripsKillSwitchWhenDdExceedsThreshold() {
        // Cumulative pnl walk: +100, +100, +100, +100, +100, -150, -150, -150
        // → peak 500, current -50, DD = 550, DD% of peak = 110% over 25%
        // threshold. Sample size (8) clears the MIN_TRADES_FOR_DD floor.
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(List.of(
                        tradeWithPnl("100"), tradeWithPnl("100"), tradeWithPnl("100"),
                        tradeWithPnl("100"), tradeWithPnl("100"),
                        tradeWithPnl("-150"), tradeWithPnl("-150"), tradeWithPnl("-150")));

        RiskGuardService.GuardVerdict verdict = guard.canOpen(accountStrategyId, "LONG");

        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("threshold"));
        // The trip MUST be persisted so subsequent calls short-circuit.
        ArgumentCaptor<AccountStrategy> captor = ArgumentCaptor.forClass(AccountStrategy.class);
        verify(accountStrategyRepository).save(captor.capture());
        AccountStrategy persisted = captor.getValue();
        assertTrue(persisted.getIsKillSwitchTripped());
        assertNotNull(persisted.getKillSwitchTrippedAt());
        assertNotNull(persisted.getKillSwitchReason());
    }

    @Test
    void allowsWhenDdBelowThreshold() {
        // Cumulative: +100, +120 → peak 120, current 120, DD = 0%.
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(accountRepository.findByAccountId(accountId)).thenReturn(Optional.of(account));
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(List.of(tradeWithPnl("100"), tradeWithPnl("20")));
        when(tradesRepository.countOpenByAccountIdAndSide(eq(accountId), anyString())).thenReturn(0L);

        RiskGuardService.GuardVerdict verdict = guard.canOpen(accountStrategyId, "LONG");

        assertTrue(verdict.allowed());
        verify(accountStrategyRepository, never()).save(any());
    }

    @Test
    void deniesWhenConcurrentLongsAtCap() {
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(accountRepository.findByAccountId(accountId)).thenReturn(Optional.of(account));
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(List.of());
        // Already at the cap of 2 longs across the account.
        when(tradesRepository.countOpenByAccountIdAndSide(accountId, "LONG")).thenReturn(2L);

        RiskGuardService.GuardVerdict verdict = guard.canOpen(accountStrategyId, "LONG");

        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("Concurrent"));
        assertEquals(2L, verdict.concurrentSameSideCount());
    }

    @Test
    void shortCapIsIndependentOfLongCap() {
        // Two longs already open — but the new entry is SHORT, which has its
        // own cap. Should be allowed.
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(accountRepository.findByAccountId(accountId)).thenReturn(Optional.of(account));
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(List.of());
        when(tradesRepository.countOpenByAccountIdAndSide(accountId, "SHORT")).thenReturn(0L);

        RiskGuardService.GuardVerdict verdict = guard.canOpen(accountStrategyId, "SHORT");

        assertTrue(verdict.allowed());
    }

    @Test
    void rearmClearsTripState() {
        strategy.setIsKillSwitchTripped(Boolean.TRUE);
        strategy.setKillSwitchTrippedAt(LocalDateTime.now());
        strategy.setKillSwitchReason("DD breach");
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(accountStrategyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountStrategy result = guard.rearm(accountStrategyId);

        assertFalse(result.getIsKillSwitchTripped());
        assertNull(result.getKillSwitchTrippedAt());
        assertNull(result.getKillSwitchReason());
    }

    @Test
    void pureLossReturnsFullDdPct() {
        // No peak ever positive — strategy bled from start. Method returns
        // 100% so the guard trips rather than letting bleeding continue.
        // Sample size (5) just clears the MIN_TRADES_FOR_DD floor.
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(List.of(
                        tradeWithPnl("-50"), tradeWithPnl("-30"),
                        tradeWithPnl("-20"), tradeWithPnl("-15"),
                        tradeWithPnl("-10")));

        BigDecimal dd = guard.computeRolling30DayDdPct(accountStrategyId);

        assertEquals(0, dd.compareTo(new BigDecimal("100.00")));
    }

    @Test
    void emptyTradeHistoryGivesZeroDd() {
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(List.of());

        BigDecimal dd = guard.computeRolling30DayDdPct(accountStrategyId);

        assertEquals(0, dd.compareTo(BigDecimal.ZERO));
    }

    /**
     * Regression: prior bug caused brand-new strategies to auto-kill on
     * the first losing trade. The pure-loss fallback returned 100% DD
     * because peak (initialised to 0) was never updated, and the kill
     * switch tripped on any default threshold. With the MIN_TRADES_FOR_DD
     * floor in place, samples below the threshold return 0.
     */
    @Test
    void singleLosingTradeDoesNotAutoTripBelowMinSampleFloor() {
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(List.of(tradeWithPnl("-50")));

        BigDecimal dd = guard.computeRolling30DayDdPct(accountStrategyId);

        assertEquals(0, dd.compareTo(BigDecimal.ZERO),
                "DD on a 1-sample history must be 0 — early-strategy auto-kill regression guard");
    }

    @Test
    void belowMinSampleFloorPassesGuardEvenWithLosses() {
        // 4 losing trades is still under the 5-trade floor, so the guard
        // must NOT trip. The kill switch only engages with enough signal.
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(accountRepository.findByAccountId(accountId)).thenReturn(Optional.of(account));
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(List.of(
                        tradeWithPnl("-30"), tradeWithPnl("-20"),
                        tradeWithPnl("-15"), tradeWithPnl("-10")));
        when(tradesRepository.countOpenByAccountIdAndSide(eq(accountId), anyString())).thenReturn(0L);

        RiskGuardService.GuardVerdict verdict = guard.canOpen(accountStrategyId, "LONG");

        assertTrue(verdict.allowed(),
                "guard must allow when sample is below the trust floor");
        verify(accountStrategyRepository, never()).save(any());
    }

    private static Trades tradeWithPnl(String pnl) {
        Trades t = new Trades();
        t.setRealizedPnlAmount(new BigDecimal(pnl));
        t.setExitTime(LocalDateTime.now().minusDays(1));
        return t;
    }
}
