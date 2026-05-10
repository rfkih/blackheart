package id.co.blackheart.engine;

import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.SpecTrace;
import id.co.blackheart.repository.SpecTraceRepository;
import id.co.blackheart.util.TradeConstant.DecisionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpecTraceLoggerTest {

    private SpecTraceRepository repository;
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        repository = mock(SpecTraceRepository.class);
        txManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(txManager.getTransaction(any())).thenReturn(status);
    }

    @Test
    void backtestRecordsAlways_evenWhenSampleRateIsZero() {
        SpecTraceLogger logger = new SpecTraceLogger(repository, txManager, 0.0);

        logger.recordTrace(spec("BBR"), backtestCtx(UUID.randomUUID()),
                hold(), 1_000_000L, null, null);

        ArgumentCaptor<SpecTrace> captor = ArgumentCaptor.forClass(SpecTrace.class);
        verify(repository).save(captor.capture());

        SpecTrace saved = captor.getValue();
        assertEquals("BBR", saved.getStrategyCode());
        assertEquals("HOLD", saved.getDecision());
        assertEquals("SCAN", saved.getPhase());
        assertEquals(1000, saved.getEvalLatencyUs(), "1ms = 1000us");
        assertNotNull(saved.getSpecSnapshot());
        assertEquals("mean_reversion_oscillator", saved.getSpecSnapshot().get("archetype"));
        assertNotNull(saved.getRules(), "rules must default to non-null array");
    }

    @Test
    void liveSampleRateZeroSkipsWrites() {
        SpecTraceLogger logger = new SpecTraceLogger(repository, txManager, 0.0);
        logger.recordTrace(spec("BBR"), liveCtx(), hold(), 1_000L, null, null);
        verify(repository, never()).save(any(SpecTrace.class));
    }

    @Test
    void errorPathPersistsErrorMetadataAndPhase() {
        SpecTraceLogger logger = new SpecTraceLogger(repository, txManager, 1.0);
        RuntimeException boom = new IllegalStateException("bad spec");

        logger.recordTrace(spec("BBR"), liveCtx(), null, 500_000L, boom, null);

        ArgumentCaptor<SpecTrace> captor = ArgumentCaptor.forClass(SpecTrace.class);
        verify(repository).save(captor.capture());
        SpecTrace saved = captor.getValue();
        assertEquals("ERROR", saved.getDecision());
        assertEquals("ERROR", saved.getPhase(), "no decision → ERROR phase");
        assertEquals("java.lang.IllegalStateException", saved.getErrorClass());
        assertEquals("bad spec", saved.getErrorMessage());
    }

    @Test
    void openLongDecisionMapsToEntryLongPhase() {
        SpecTraceLogger logger = new SpecTraceLogger(repository, txManager, 1.0);
        StrategyDecision d = StrategyDecision.builder()
                .decisionType(DecisionType.OPEN_LONG)
                .reason("rsi oversold + bb tag")
                .build();

        logger.recordTrace(spec("BBR"), liveCtx(), d, 250_000L, null, null);

        ArgumentCaptor<SpecTrace> captor = ArgumentCaptor.forClass(SpecTrace.class);
        verify(repository).save(captor.capture());
        SpecTrace saved = captor.getValue();
        assertEquals("ENTRY_LONG", saved.getPhase());
        assertEquals("OPEN_LONG", saved.getDecision());
        assertEquals("rsi oversold + bb tag", saved.getDecisionReason());
        assertNull(saved.getErrorClass());
    }

    @Test
    void specSnapshotIsDefensivelyCopied() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("rsiOversold", 30);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("setupLong", "BB_LOWER");
        StrategySpec spec = StrategySpec.builder()
                .strategyCode("BBR")
                .strategyName("Bollinger Reversal")
                .archetype("mean_reversion_oscillator")
                .archetypeVersion(1)
                .specSchemaVersion(1)
                .params(params)
                .body(body)
                .build();

        SpecTraceLogger logger = new SpecTraceLogger(repository, txManager, 1.0);
        logger.recordTrace(spec, backtestCtx(UUID.randomUUID()), hold(), 0L, null, null);

        ArgumentCaptor<SpecTrace> captor = ArgumentCaptor.forClass(SpecTrace.class);
        verify(repository).save(captor.capture());
        Map<String, Object> snap = captor.getValue().getSpecSnapshot();

        // Mutate the originals AFTER record; snapshot must not see the change.
        params.put("rsiOversold", 999);
        body.put("setupLong", "MUTATED");

        @SuppressWarnings("unchecked")
        Map<String, Object> snappedParams = (Map<String, Object>) snap.get("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> snappedBody = (Map<String, Object>) snap.get("body");
        assertEquals(30, snappedParams.get("rsiOversold"));
        assertEquals("BB_LOWER", snappedBody.get("setupLong"));
    }

    @Test
    void rulesArgumentFlowsThroughToPersistedRow() {
        SpecTraceLogger logger = new SpecTraceLogger(repository, txManager, 1.0);
        Map<String, Object> r0 = new HashMap<>();
        r0.put("index", 0);
        r0.put("type", "rsi_extreme");
        r0.put("result", true);
        r0.put("value", 22.4);

        logger.recordTrace(spec("BBR"), liveCtx(), hold(), 0L, null, List.of(r0));

        ArgumentCaptor<SpecTrace> captor = ArgumentCaptor.forClass(SpecTrace.class);
        verify(repository).save(captor.capture());
        List<Map<String, Object>> rules = captor.getValue().getRules();
        assertEquals(1, rules.size());
        assertEquals("rsi_extreme", rules.get(0).get("type"));
        assertEquals(true, rules.get(0).get("result"));
    }

    @Test
    void repositoryFailureDoesNotPropagate() {
        SpecTraceLogger logger = new SpecTraceLogger(repository, txManager, 1.0);
        when(repository.save(any(SpecTrace.class)))
                .thenThrow(new RuntimeException("DB down"));

        // Must not throw — trace writes are best-effort.
        logger.recordTrace(spec("BBR"), liveCtx(), hold(), 0L, null, null);
        assertTrue(true);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static StrategySpec spec(String code) {
        return StrategySpec.builder()
                .strategyCode(code)
                .strategyName(code)
                .archetype("mean_reversion_oscillator")
                .archetypeVersion(1)
                .specSchemaVersion(1)
                .params(new HashMap<>())
                .body(new HashMap<>())
                .build();
    }

    private static StrategyDecision hold() {
        return StrategyDecision.builder().decisionType(DecisionType.HOLD).build();
    }

    private static EnrichedStrategyContext backtestCtx(UUID runId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", "backtest");
        meta.put("backtestRunId", runId);
        return baseCtx(meta);
    }

    private static EnrichedStrategyContext liveCtx() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", "live");
        return baseCtx(meta);
    }

    private static EnrichedStrategyContext baseCtx(Map<String, Object> meta) {
        MarketData md = new MarketData();
        md.setSymbol("BTCUSDT");
        md.setInterval("1h");
        md.setEndTime(LocalDateTime.parse("2026-04-29T10:00:00"));

        AccountStrategy as = AccountStrategy.builder()
                .accountStrategyId(UUID.randomUUID())
                .strategyCode("BBR")
                .build();

        return EnrichedStrategyContext.builder()
                .accountStrategy(as)
                .marketData(md)
                .interval("1h")
                .executionMetadata(meta)
                .hasOpenPosition(false)
                .build();
    }
}
