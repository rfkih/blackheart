package id.co.blackheart.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.BinanceAssetRequest;
import id.co.blackheart.dto.request.BinanceOrderDetailRequest;
import id.co.blackheart.dto.request.BinanceOrderRequest;
import id.co.blackheart.dto.response.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BinanceClientService {
    private final RestTemplate restTemplate;
    private final RestTemplate binanceRestTemplate;
    private final ObjectMapper objectMapper;

    @Value("${nodejs.api.base-url}")
    private String baseUrl;

    public BinanceClientService(RestTemplate restTemplate,
                                @Qualifier("binanceRestTemplate") RestTemplate binanceRestTemplate,
                                ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.binanceRestTemplate = binanceRestTemplate;
        this.objectMapper = objectMapper;
    }

    public double getCurrentBtcPrice() {
        String url = UriComponentsBuilder
                .fromHttpUrl("https://api.binance.com/api/v3/ticker/price")
                .queryParam("symbol", "BTCUSDT")
                .toUriString();

        BinancePriceResponse response = restTemplate.getForObject(url, BinancePriceResponse.class);

        if (response != null) {
            return Double.parseDouble(response.getPrice());
        } else {
            throw new RuntimeException("Failed to fetch BTC price from Binance");
        }
    }

    /**
     * Bulk-fetches latest prices for the given symbols from Binance's public
     * ticker endpoint. Uses the bounded-timeout {@code binanceRestTemplate} so
     * a slow upstream cannot stall callers (e.g. portfolio reads). Symbols
     * absent from the returned map are either invalid (no USDT pair) or
     * Binance failed to price them — callers should treat them as missing.
     *
     * <p>Strategy: try the bulk endpoint first (one HTTP call). Binance fails
     * the whole batch with HTTP 400 if any symbol is invalid, so on 400 we
     * retry per-symbol so valid ones still resolve. Any other failure returns
     * an empty map — callers fall back to negative-cache + ZERO usdtValue.
     */
    public Map<String, BigDecimal> getLatestPrices(Collection<String> symbols) {
        if (CollectionUtils.isEmpty(symbols)) {
            return Collections.emptyMap();
        }
        Set<String> distinct = symbols.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (CollectionUtils.isEmpty(distinct)) {
            return Collections.emptyMap();
        }

        String symbolsJson = distinct.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        String bulkUrl = UriComponentsBuilder
                .fromHttpUrl("https://api.binance.com/api/v3/ticker/price")
                .queryParam("symbols", symbolsJson)
                .build()
                .toUriString();

        try {
            BinancePriceResponse[] response =
                    binanceRestTemplate.getForObject(bulkUrl, BinancePriceResponse[].class);
            return parsePriceArray(response);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().is4xxClientError()) {
                // 400 = at least one symbol invalid; bulk fails atomically.
                // Retry per-symbol so the valid ones still resolve.
                log.debug("Binance bulk price returned {}; falling back per-symbol",
                        e.getStatusCode());
                return fetchPricesPerSymbol(distinct);
            }
            log.warn("Binance bulk price fetch failed | status={} symbols={}",
                    e.getStatusCode(), distinct);
            return Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Binance bulk price fetch error | symbols={}", distinct, e);
            return Collections.emptyMap();
        }
    }

    private Map<String, BigDecimal> fetchPricesPerSymbol(Collection<String> symbols) {
        Map<String, BigDecimal> result = new HashMap<>();
        for (String symbol : symbols) {
            String url = UriComponentsBuilder
                    .fromHttpUrl("https://api.binance.com/api/v3/ticker/price")
                    .queryParam("symbol", symbol)
                    .build()
                    .toUriString();
            try {
                BinancePriceResponse r =
                        binanceRestTemplate.getForObject(url, BinancePriceResponse.class);
                if (r != null && r.getPrice() != null) {
                    try {
                        result.put(symbol, new BigDecimal(r.getPrice()));
                    } catch (NumberFormatException ex) {
                        log.warn("Bad price from Binance | symbol={} price={}",
                                symbol, r.getPrice());
                    }
                }
            } catch (HttpStatusCodeException ignored) {
                // Symbol genuinely doesn't exist on Binance — caller will
                // negative-cache it.
            } catch (Exception e) {
                log.warn("Per-symbol price fetch failed | symbol={}", symbol, e);
            }
        }
        return result;
    }

    private Map<String, BigDecimal> parsePriceArray(BinancePriceResponse[] response) {
        if (response == null) {
            return Collections.emptyMap();
        }
        Map<String, BigDecimal> result = new HashMap<>();
        for (BinancePriceResponse r : response) {
            if (r == null || r.getSymbol() == null || r.getPrice() == null) continue;
            try {
                result.put(r.getSymbol(), new BigDecimal(r.getPrice()));
            } catch (NumberFormatException ex) {
                log.warn("Bad price from Binance | symbol={} price={}",
                        r.getSymbol(), r.getPrice());
            }
        }
        return result;
    }

    public BinanceAssetResponse getBinanceAssetDetails(BinanceAssetRequest binanceAssetRequest) {
        ResponseEntity<String> response = postRequest(
                baseUrl + "/api/get-asset-binance",
                binanceAssetRequest,
                buildJsonHeaders()
        );
        List<BinanceAssetDto> assets = decodeResponse(response, new TypeReference<List<BinanceAssetDto>>() {});
        BinanceAssetResponse result = new BinanceAssetResponse();
        result.setAssets(assets);
        return result;
    }

    public BinanceOrderDetailResponse orderDetailBinance(BinanceOrderDetailRequest orderDetailRequest) {
        ResponseEntity<String> response = postRequest(
                baseUrl + "/api/order-detail-binance",
                orderDetailRequest,
                buildBinanceHeaders(orderDetailRequest.getApiKey())
        );
        return decodeResponse(response, new TypeReference<BinanceOrderDetailResponse>() {});
    }

    public BinanceOrderResponse binanceMarketOrder(BinanceOrderRequest binanceOrderRequest) {
        ResponseEntity<String> response = postRequest(
                baseUrl + "/api/place-market-order-binance",
                binanceOrderRequest,
                buildJsonHeaders()
        );
        return decodeResponse(response, new TypeReference<BinanceOrderResponse>() {});
    }

    private HttpHeaders buildJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders buildBinanceHeaders(String apiKey) {
        HttpHeaders headers = buildJsonHeaders();
        headers.set("X-MBX-APIKEY", apiKey);
        return headers;
    }

    private ResponseEntity<String> postRequest(String url, Object body, HttpHeaders headers) {
        try {
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            return restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        } catch (HttpStatusCodeException ex) {
            log.error("API Error: {}", ex.getResponseBodyAsString());
            throw ex;
        }
    }

    private <T> T decodeResponse(ResponseEntity<String> response, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(response.getBody(), typeRef);
        } catch (IOException e) {
            log.error("Error decoding response: {}", e.getMessage());
            throw new RuntimeException("Failed to decode response", e);
        }
    }
}
