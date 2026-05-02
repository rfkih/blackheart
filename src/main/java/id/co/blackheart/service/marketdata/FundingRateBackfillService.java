package id.co.blackheart.service.marketdata;

import id.co.blackheart.client.FapiClient;
import id.co.blackheart.model.FundingRate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Phase 4 step 2 — historical + incremental funding-rate ingestion.
 *
 * <p>One-shot historical backfill: {@link #backfillHistorical(String,
 * LocalDateTime)} loops forward from the supplied start instant in 1000-row
 * pages. Incremental ingest (Phase 4.8 scheduler): {@link
 * #ingestIncremental(String)} starts from {@code latestFundingTime + 1ms} and
 * pulls anything new. Both call paths reuse the same idempotent
 * {@link FundingRateService#upsertAll(List)} sink, so re-running the backfill
 * after the scheduler has run (or vice versa) is safe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FundingRateBackfillService {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final int HARD_PAGE_CAP = 200; // ~200k rows; protects against runaway loops

    private final FapiClient fapiClient;
    private final FundingRateService fundingRateService;

    /**
     * Pull every funding event for {@code symbol} from {@code startInclusive}
     * up to "now" and upsert into {@code funding_rate_history}. Idempotent —
     * existing rows are skipped by PK.
     */
    public BackfillResult backfillHistorical(String symbol, LocalDateTime startInclusive) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }
        if (startInclusive == null) {
            throw new IllegalArgumentException("startInclusive cannot be null");
        }
        long startMs = startInclusive.toInstant(ZoneOffset.UTC).toEpochMilli();
        return paginateForward(symbol, startMs, "historical");
    }

    /**
     * Incremental tick — pull anything newer than the last persisted
     * fundingTime for {@code symbol}. If the table is cold for this symbol,
     * caller should run {@link #backfillHistorical} first; this method
     * intentionally returns 0 fetched rather than silently backfilling
     * unbounded history on a fresh DB.
     */
    public BackfillResult ingestIncremental(String symbol) {
        return fundingRateService.latestFundingTime(symbol)
                .map(latest -> {
                    long startMs = latest.toInstant(ZoneOffset.UTC).toEpochMilli() + 1L;
                    return paginateForward(symbol, startMs, "incremental");
                })
                .orElseGet(() -> {
                    log.info("funding_rate ingest skipped — no prior history for symbol={}", symbol);
                    return new BackfillResult(symbol, 0, 0, 0, false);
                });
    }

    private BackfillResult paginateForward(String symbol, long startMs, String mode) {
        int pages = 0;
        int fetched = 0;
        int inserted = 0;
        long cursor = startMs;
        long startedNanos = System.nanoTime();
        boolean truncated = false;

        while (pages < HARD_PAGE_CAP) {
            List<FundingRate> page = fapiClient.fetchFundingRates(
                    symbol, cursor, null, FapiClient.FUNDING_RATE_MAX_LIMIT);
            pages++;
            if (page.isEmpty()) break;

            int newRows = fundingRateService.upsertAll(page);
            fetched += page.size();
            inserted += newRows;

            if (page.size() < FapiClient.FUNDING_RATE_MAX_LIMIT) break;

            // Advance cursor past the last fundingTime we just saw. +1ms keeps
            // the next page strictly forward and avoids infinite loops if a
            // page boundary lands on a duplicate fundingTime.
            LocalDateTime lastFundingTime = page.get(page.size() - 1).getFundingTime();
            cursor = lastFundingTime.toInstant(ZoneOffset.UTC).toEpochMilli() + 1L;

            if (pages == HARD_PAGE_CAP) {
                truncated = true;
            }
        }

        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        log.info("funding_rate {} backfill done | symbol={} pages={} fetched={} inserted={} truncated={} elapsedMs={}",
                mode, symbol, pages, fetched, inserted, truncated, elapsedMs);
        if (truncated) {
            log.warn("funding_rate backfill hit HARD_PAGE_CAP for symbol={} — caller should re-run", symbol);
        }
        return new BackfillResult(symbol, pages, fetched, inserted, truncated);
    }

    /**
     * Outcome of a backfill run. {@code truncated=true} means the loop hit
     * {@link #HARD_PAGE_CAP} before exhausting Binance's history — caller
     * should re-run with a later {@code startInclusive} to pick up where this
     * left off.
     */
    public record BackfillResult(String symbol, int pages, int fetched, int inserted, boolean truncated) {}
}
