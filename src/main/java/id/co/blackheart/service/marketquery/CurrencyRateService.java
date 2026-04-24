package id.co.blackheart.service.marketquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.response.CurrencyRatesResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches + caches the two rates needed to render balances in USD / IDR / BTC.
 *
 * <p>Both sources are public and keyless — Binance for BTC/USDT mid, and
 * Frankfurter (ECB reference rates) for USD → IDR. The browser is blocked from
 * reaching them directly by the frontend CSP, so the API acts as a proxy.
 *
 * <p>Results are cached for {@link #CACHE_TTL_MS}; if a refresh fails we keep
 * serving the previous value so a blip in the external API doesn't freeze the
 * UI at zero. When no cached value exists yet we fall back to a hard-coded
 * sentinel so {@code /rates} always returns something usable on first boot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrencyRateService {

    /** One minute is short enough for live feel, long enough to stay well
     *  under Binance + Frankfurter rate limits even under burst load. */
    private static final long CACHE_TTL_MS = 60_000L;

    private static final String BINANCE_URL =
            "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT";
    private static final String FX_URL =
            "https://api.frankfurter.app/latest?from=USD&to=IDR";

    /** Sane startup defaults so a cold boot never serves zeros. Refreshed on
     *  the first successful fetch. */
    private static final BigDecimal FALLBACK_BTC_USDT = new BigDecimal("65000");
    private static final BigDecimal FALLBACK_IDR_USD = new BigDecimal("16000");

    private final AtomicReference<Snapshot> cache = new AtomicReference<>(null);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CurrencyRatesResponse getRates() {
        Snapshot cached = cache.get();
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.timestamp() < CACHE_TTL_MS) {
            return toResponse(cached);
        }

        Snapshot refreshed = fetchFresh(cached);
        cache.set(refreshed);
        return toResponse(refreshed);
    }

    private Snapshot fetchFresh(Snapshot previous) {
        BigDecimal btc = fetchBtcUsdt(previous);
        BigDecimal idr = fetchIdrUsd(previous);
        return new Snapshot(btc, idr, System.currentTimeMillis());
    }

    private BigDecimal fetchBtcUsdt(Snapshot previous) {
        try {
            RestTemplate rest = new RestTemplate();
            ResponseEntity<String> resp = rest.getForEntity(BINANCE_URL, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("BTC/USDT fetch non-2xx: {}", resp.getStatusCode());
                return fallbackBtc(previous);
            }
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode price = root.get("price");
            if (price == null || !price.isTextual()) return fallbackBtc(previous);
            return new BigDecimal(price.asText());
        } catch (Exception e) {
            log.warn("BTC/USDT fetch failed: {}", e.getMessage());
            return fallbackBtc(previous);
        }
    }

    private BigDecimal fetchIdrUsd(Snapshot previous) {
        try {
            RestTemplate rest = new RestTemplate();
            ResponseEntity<String> resp = rest.getForEntity(FX_URL, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("IDR FX fetch non-2xx: {}", resp.getStatusCode());
                return fallbackIdr(previous);
            }
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode idr = root.path("rates").path("IDR");
            if (!idr.isNumber()) return fallbackIdr(previous);
            return new BigDecimal(idr.asText());
        } catch (Exception e) {
            log.warn("IDR FX fetch failed: {}", e.getMessage());
            return fallbackIdr(previous);
        }
    }

    private BigDecimal fallbackBtc(Snapshot previous) {
        return previous != null ? previous.btcUsdt() : FALLBACK_BTC_USDT;
    }

    private BigDecimal fallbackIdr(Snapshot previous) {
        return previous != null ? previous.idrUsd() : FALLBACK_IDR_USD;
    }

    private CurrencyRatesResponse toResponse(Snapshot s) {
        return CurrencyRatesResponse.builder()
                .btcUsdt(s.btcUsdt())
                .idrUsd(s.idrUsd())
                .timestamp(s.timestamp())
                .build();
    }

    private record Snapshot(BigDecimal btcUsdt, BigDecimal idrUsd, long timestamp) {}
}
