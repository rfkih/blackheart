package id.co.blackheart.util;

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * Tiny helper for splitting a Binance trading pair into base/quote.
 * Used by the live execution path so balance lookups, log messages, and
 * order construction can derive {@code BTC} / {@code USDT} from
 * {@code BTCUSDT} instead of hard-coding the literal — a prerequisite for
 * eventually supporting ETHUSDT, SOLUSDT, etc. without scattered edits.
 *
 * <p>Recognised quote suffixes are the stable / large-cap quotes Binance
 * actually uses; an unknown suffix throws so a typo'd config doesn't
 * silently degrade to a half-parsed pair.
 */
public final class SymbolUtils {

    /** Order matters: longest first so {@code FDUSD} doesn't match before
     *  {@code USD} on a hypothetical {@code XYZUSD} pair. */
    private static final List<String> KNOWN_QUOTES = List.of(
            "FDUSD", "BUSD", "TUSD", "USDC", "USDT", "BTC", "ETH", "BNB"
    );

    private SymbolUtils() {}

    public static String quoteAsset(String symbol) {
        return matchQuote(symbol);
    }

    public static String baseAsset(String symbol) {
        String suffix = matchQuote(symbol);
        return symbol.substring(0, symbol.length() - suffix.length());
    }

    private static String matchQuote(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        String upper = symbol.toUpperCase();
        for (String q : KNOWN_QUOTES) {
            if (upper.length() > q.length() && upper.endsWith(q)) {
                return q;
            }
        }
        throw new IllegalArgumentException("Unrecognised quote asset in symbol: " + symbol);
    }
}
