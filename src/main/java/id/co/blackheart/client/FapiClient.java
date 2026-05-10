package id.co.blackheart.client;

import id.co.blackheart.model.FundingRate;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Thin client for Binance USD-M futures public endpoints (fapi.binance.com).
 * Phase 4 step 2 — funding-rate history fetch only. The endpoint is unauthed,
 * so we don't share signed plumbing with {@link BinanceClientService}.
 *
 * <p>Reference: <a href="https://binance-docs.github.io/apidocs/futures/en/#get-funding-rate-history">
 * GET /fapi/v1/fundingRate</a>. Returns rows ascending by fundingTime, max 1000
 * per page. Pagination strategy (used by {@link
 * id.co.blackheart.service.marketdata.FundingRateBackfillService}): seed
 * {@code startTime} from the last persisted fundingTime + 1ms and loop until
 * the page is short.
 */
@Component
@Slf4j
public class FapiClient {

    private static final String BASE_URL = "https://fapi.binance.com";
    public static final int FUNDING_RATE_MAX_LIMIT = 1000;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int RETRY_MAX_ATTEMPTS = 3;
    private static final Duration RETRY_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration RETRY_MAX_BACKOFF = Duration.ofSeconds(10);

    /** Funding-rate JSON field name — used 3× when materialising the row.
     *  Constant prevents drift if Binance ever renames the field. */
    private static final String FIELD_MARK_PRICE = "markPrice";

    /** Externalised so a deployment can point at a non-default path (e.g. an
     *  internal proxy) without recompiling. Default matches Binance's
     *  public futures API. */
    private final String fundingRatePath;

    private final WebClient webClient = WebClient.builder()
            .baseUrl(BASE_URL)
            .clientConnector(new ReactorClientHttpConnector(
                    HttpClient.create()
                            .responseTimeout(RESPONSE_TIMEOUT)
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)))
            .build();

    public FapiClient(
            @Value("${app.binance.fapi.funding-rate-path:/fapi/v1/fundingRate}") String fundingRatePath
    ) {
        this.fundingRatePath = fundingRatePath;
    }

    /**
     * Fetch one page of funding-rate events for {@code symbol}. Both bounds
     * are inclusive at the millisecond level, in line with Binance fapi
     * semantics. Returned rows are ascending by {@code fundingTime}; when the
     * list size equals {@link #FUNDING_RATE_MAX_LIMIT} the caller must
     * paginate by advancing {@code startTimeMs}.
     */
    public List<FundingRate> fetchFundingRates(String symbol,
                                               Long startTimeMs,
                                               Long endTimeMs,
                                               int limit) {
        if (!StringUtils.hasText(symbol)) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }
        int boundedLimit = Math.clamp(limit, 1, FUNDING_RATE_MAX_LIMIT);

        String response = webClient.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder
                            .path(fundingRatePath)
                            .queryParam("symbol", symbol)
                            .queryParam("limit", boundedLimit);
                    if (startTimeMs != null) b.queryParam("startTime", startTimeMs);
                    if (endTimeMs != null) b.queryParam("endTime", endTimeMs);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(RETRY_MAX_ATTEMPTS, RETRY_INITIAL_BACKOFF)
                        .maxBackoff(RETRY_MAX_BACKOFF)
                        .filter(FapiClient::isTransient)
                        .doBeforeRetry(rs -> log.warn(
                                "fapi retry symbol={} attempt={} cause={}",
                                symbol, rs.totalRetries() + 1, rs.failure().toString())))
                .block();

        if (!StringUtils.hasText(response)) {
            return List.of();
        }
        JSONArray arr = new JSONArray(response);
        List<FundingRate> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            LocalDateTime fundingTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(o.getLong("fundingTime")), UTC);
            BigDecimal rate = new BigDecimal(o.getString("fundingRate"));
            BigDecimal markPrice = o.has(FIELD_MARK_PRICE) && !o.isNull(FIELD_MARK_PRICE)
                    ? safeBigDecimal(o.getString(FIELD_MARK_PRICE))
                    : null;
            out.add(FundingRate.builder()
                    .symbol(o.getString("symbol"))
                    .fundingTime(fundingTime)
                    .fundingRate(rate)
                    .markPrice(markPrice)
                    .build());
        }
        return out;
    }

    /** 429 / 5xx / I/O / timeout = transient, retry. 4xx (other) = client bug, fail fast. */
    private static boolean isTransient(Throwable t) {
        if (t instanceof WebClientResponseException w) {
            int status = w.getStatusCode().value();
            if (status == 429 || status >= 500) return true;
            // Body-read failures surface as a 200 WebClientResponseException
            // (headers arrived, body stalled). The transient cause may be the
            // direct getCause() or wrapped one level deeper depending on the
            // Reactor version, so walk the chain rather than relying on a
            // single instanceof check.
            return hasTransientCause(w);
        }
        return t instanceof TimeoutException
                || t instanceof SocketException
                || t instanceof IOException;
    }

    private static boolean hasTransientCause(Throwable t) {
        Throwable cause = t.getCause();
        while (cause != null) {
            if (cause instanceof ReadTimeoutException
                    || cause instanceof TimeoutException
                    || cause instanceof SocketException
                    || cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static BigDecimal safeBigDecimal(String s) {
        if (!StringUtils.hasText(s)) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException ex) {
            log.warn("fapi: unparseable BigDecimal '{}'", s);
            return null;
        }
    }
}
