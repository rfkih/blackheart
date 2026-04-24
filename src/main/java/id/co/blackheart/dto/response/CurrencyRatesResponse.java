package id.co.blackheart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response for {@code GET /api/v1/market/rates}. Powers the frontend's
 * display-currency toggle (USD / IDR / BTC) without exposing the browser to
 * third-party CORS/CSP egress.
 *
 * <p>All conversions treat USDT as the stable unit — one USDT ≈ one USD. The
 * frontend divides by {@link #btcUsdt} to convert USDT → BTC, and multiplies
 * by {@link #idrUsd} to convert USDT → IDR.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrencyRatesResponse {

    /** Current BTC/USDT mid-price from Binance — USDT per one BTC. */
    private BigDecimal btcUsdt;

    /** Current USD → IDR spot rate — IDR per one USD. */
    private BigDecimal idrUsd;

    /** Epoch millis at which the rates were captured. Lets the client show
     *  "as of N minutes ago" if it wants. */
    private long timestamp;
}
