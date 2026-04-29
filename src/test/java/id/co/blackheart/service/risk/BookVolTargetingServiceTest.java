package id.co.blackheart.service.risk;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.PortfolioRepository;
import id.co.blackheart.repository.TradesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Locks in the size-scaling contract. The single most important property:
 * the toggle is off by default → the service is a no-op. Everything else
 * (vol estimation, concurrency haircut, clamping) only kicks in when the
 * user explicitly opted in.
 */
@ExtendWith(MockitoExtension.class)
class BookVolTargetingServiceTest {

    @Mock private AccountStrategyRepository accountStrategyRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private TradesRepository tradesRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @InjectMocks private BookVolTargetingService service;

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
                .capitalAllocationPct(new BigDecimal("100"))
                .build();
        account = new Account();
        account.setAccountId(accountId);
        account.setVolTargetingEnabled(Boolean.FALSE);
        account.setBookVolTargetPct(new BigDecimal("15.00"));
        account.setMaxConcurrentLongs(2);
        account.setMaxConcurrentShorts(2);
    }

    @Test
    void noOpWhenVolTargetingDisabled() {
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(accountRepository.findByAccountId(accountId)).thenReturn(Optional.of(account));
        BigDecimal base = new BigDecimal("100");

        BookVolTargetingService.SizingScale s = service.scale(accountStrategyId, "LONG", base);

        assertEquals(0, s.scaledSize().compareTo(base));
        assertEquals(0, s.volScale().compareTo(BigDecimal.ONE));
        assertEquals(0, s.concurrencyHaircut().compareTo(BigDecimal.ONE));
    }

    @Test
    void noOpWhenStrategyMissing() {
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.empty());
        BigDecimal base = new BigDecimal("100");

        BookVolTargetingService.SizingScale s = service.scale(accountStrategyId, "LONG", base);

        assertEquals(0, s.scaledSize().compareTo(base));
    }

    @Test
    void noOpOnZeroOrNegativeBaseSize() {
        BookVolTargetingService.SizingScale s = service.scale(accountStrategyId, "LONG", BigDecimal.ZERO);
        assertEquals(0, s.scaledSize().compareTo(BigDecimal.ZERO));
    }

    @Test
    void noOpWhenSampleTooThin() {
        // Vol-targeting on, but only 5 days of trades — below MIN_DAYS_FOR_VOL_ESTIMATE.
        // Concurrency haircut still applies but vol scale falls back to 1.0.
        account.setVolTargetingEnabled(Boolean.TRUE);
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(accountRepository.findByAccountId(accountId)).thenReturn(Optional.of(account));
        when(portfolioRepository.findByAccountIdAndAsset(accountId, "USDT"))
                .thenReturn(Optional.of(usdtPortfolio("10000")));
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(tradesAcrossNDays(5, "10"));
        when(tradesRepository.countOpenByAccountIdAndSide(eq(accountId), anyString()))
                .thenReturn(0L);

        BigDecimal base = new BigDecimal("100");
        BookVolTargetingService.SizingScale s = service.scale(accountStrategyId, "LONG", base);

        // Vol scale falls back to 1.0 (thin sample), concurrency 1/(1+0)=1
        // → scaled equals base.
        assertEquals(0, s.volScale().compareTo(BigDecimal.ONE));
        assertEquals(0, s.concurrencyHaircut().compareTo(BigDecimal.ONE));
    }

    @Test
    void concurrencyHaircutShrinksWhenSiblingsOpen() {
        account.setVolTargetingEnabled(Boolean.TRUE);
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(accountRepository.findByAccountId(accountId)).thenReturn(Optional.of(account));
        when(portfolioRepository.findByAccountIdAndAsset(accountId, "USDT"))
                .thenReturn(Optional.of(usdtPortfolio("10000")));
        // No history → vol scale = 1.0.
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(List.of());
        // Two longs already open across the account → haircut = 1/3.
        when(tradesRepository.countOpenByAccountIdAndSide(accountId, "LONG"))
                .thenReturn(2L);

        BigDecimal base = new BigDecimal("300");
        BookVolTargetingService.SizingScale s = service.scale(accountStrategyId, "LONG", base);

        // 300 × 1.0 × (1/3) = 100. Allow rounding.
        assertEquals(0, s.concurrencyHaircut().compareTo(new BigDecimal("0.3333")));
        assertTrue(s.scaledSize().subtract(new BigDecimal("100")).abs()
                .compareTo(new BigDecimal("0.05")) < 0,
                "expected ~100, got " + s.scaledSize());
    }

    @Test
    void volScaleClampsToMaxWhenStrategyVolNearZero() {
        account.setVolTargetingEnabled(Boolean.TRUE);
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(accountRepository.findByAccountId(accountId)).thenReturn(Optional.of(account));
        when(portfolioRepository.findByAccountIdAndAsset(accountId, "USDT"))
                .thenReturn(Optional.of(usdtPortfolio("10000")));
        // 15 days of essentially zero P&L → near-zero σ.
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(tradesAcrossNDays(15, "0.01"));
        when(tradesRepository.countOpenByAccountIdAndSide(eq(accountId), anyString()))
                .thenReturn(0L);

        BookVolTargetingService.SizingScale s = service.scale(
                accountStrategyId, "LONG", new BigDecimal("100"));

        // Hit the upper bound — service must NOT return an absurd 50× scale.
        assertTrue(s.volScale().compareTo(new BigDecimal("4.0001")) <= 0,
                "vol scale must clamp at 4×, got " + s.volScale());
    }

    @Test
    void volScaleShrinksHighVolStrategy() {
        account.setVolTargetingEnabled(Boolean.TRUE);
        when(accountStrategyRepository.findById(accountStrategyId)).thenReturn(Optional.of(strategy));
        when(accountRepository.findByAccountId(accountId)).thenReturn(Optional.of(account));
        when(portfolioRepository.findByAccountIdAndAsset(accountId, "USDT"))
                .thenReturn(Optional.of(usdtPortfolio("10000")));
        // Alternating big +/- daily P&L → high realized vol.
        List<Trades> highVol = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusDays(20);
        for (int i = 0; i < 15; i++) {
            // Alternates +500 / -500 daily on a 10000 baseline = ±5%/day
            // → annualized σ = 5 × √252 ≈ 79%, well above the 15% target.
            highVol.add(tradeOn(base.plusDays(i), i % 2 == 0 ? "500" : "-500"));
        }
        when(tradesRepository.findClosedByAccountStrategyIdSince(eq(accountStrategyId), any()))
                .thenReturn(highVol);
        when(tradesRepository.countOpenByAccountIdAndSide(eq(accountId), anyString()))
                .thenReturn(0L);

        BookVolTargetingService.SizingScale s = service.scale(
                accountStrategyId, "LONG", new BigDecimal("100"));

        assertTrue(s.volScale().compareTo(BigDecimal.ONE) < 0,
                "high-vol strategy should be scaled DOWN (got volScale=" + s.volScale() + ")");
        // Floor at 0.25 — verify it doesn't go below.
        assertTrue(s.volScale().compareTo(new BigDecimal("0.2499")) >= 0);
    }

    private static Portfolio usdtPortfolio(String balance) {
        Portfolio p = new Portfolio();
        p.setAsset("USDT");
        p.setBalance(new BigDecimal(balance));
        return p;
    }

    private static Trades tradeOn(LocalDateTime when, String pnl) {
        Trades t = new Trades();
        t.setExitTime(when);
        t.setRealizedPnlAmount(new BigDecimal(pnl));
        return t;
    }

    /** N daily trades evenly stretched across (now-N, now). One trade per day,
     *  each closing at noon on its day. */
    private static List<Trades> tradesAcrossNDays(int days, String dailyPnl) {
        List<Trades> out = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusDays(days);
        for (int i = 0; i < days; i++) {
            out.add(tradeOn(base.plusDays(i).withHour(12), dailyPnl));
        }
        return out;
    }
}
