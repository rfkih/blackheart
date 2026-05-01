package id.co.blackheart.service.observability;

import org.springframework.stereotype.Component;

/**
 * Maps a logging event onto the developer-agent triage severities.
 *
 * <ul>
 *   <li><b>CRITICAL</b> — anything on the live trading execution path or the
 *       Binance order client. A bug here can lose real capital. Triggers a
 *       Telegram alert.</li>
 *   <li><b>HIGH</b> — service-layer code outside the trading hot path
 *       (research, scheduler, persistence). Won't bleed money but breaks
 *       features. First occurrence pages; further occurrences throttled.</li>
 *   <li><b>MEDIUM</b> — anything else (controllers, validation, third-party
 *       client glitches). Logged for the dashboard, no alert.</li>
 *   <li><b>LOW</b> — reserved for known-noisy paths flagged in MDC.</li>
 * </ul>
 *
 * <p>Classification reads only the logger name and exception class. Both are
 * available without reflection in the appender, so this is safe to call from
 * the Logback thread.
 */
@Component
public class SeverityClassifier {

    public String classify(String loggerName, String exceptionClass) {
        if (loggerName == null) return "MEDIUM";
        String n = loggerName;

        // ── Live trading execution path: CRITICAL ────────────────────────────
        if (n.startsWith("id.co.blackheart.service.live")
                || n.startsWith("id.co.blackheart.service.trade")
                || n.startsWith("id.co.blackheart.client.BinanceClient")
                || n.startsWith("id.co.blackheart.stream")
                || n.contains("LiveTradingDecisionExecutor")
                || n.contains("LiveTradingCoordinator")
                || n.contains("LiveOrchestratorCoordinator")
                || n.contains("BinanceWebSocket")) {
            return "CRITICAL";
        }

        // OutOfMemory / StackOverflow on any thread is always CRITICAL.
        if (exceptionClass != null
                && (exceptionClass.equals("java.lang.OutOfMemoryError")
                || exceptionClass.equals("java.lang.StackOverflowError"))) {
            return "CRITICAL";
        }

        // ── Frontend errors (logger names prefixed by the controller) ────────
        // The /api/v1/errors controller stamps loggerName as
        // "frontend.<route>" (e.g. "frontend.trade.NewOrderForm"). Routes
        // that hand off to live capital decisions (place/cancel order, kill
        // switch, promotion) page as HIGH — a broken UI on those paths still
        // costs the operator real money even though the JS itself isn't on
        // the trading hot path. Everything else from the frontend is MEDIUM.
        if (n.startsWith("frontend.")) {
            if (n.startsWith("frontend.trade")
                    || n.startsWith("frontend.kill-switch")
                    || n.startsWith("frontend.strategy-promotion")
                    || n.startsWith("frontend.account-strategy")) {
                return "HIGH";
            }
            return "MEDIUM";
        }

        // Middleware (FastAPI, Node) — Phase C. Same shape as frontend, with
        // a "middleware." prefix. Default MEDIUM; HIGH only if it sits in the
        // request path of a live decision.
        if (n.startsWith("middleware.")) {
            if (n.startsWith("middleware.trade")
                    || n.startsWith("middleware.kill-switch")) {
                return "HIGH";
            }
            return "MEDIUM";
        }

        // ── Service-layer (non-hot-path) and persistence: HIGH ───────────────
        if (n.startsWith("id.co.blackheart.service")
                || n.startsWith("id.co.blackheart.repository")
                || n.startsWith("id.co.blackheart.engine")
                || n.startsWith("org.flywaydb")
                || n.startsWith("org.hibernate")
                || n.startsWith("com.zaxxer.hikari")) {
            return "HIGH";
        }

        return "MEDIUM";
    }
}
