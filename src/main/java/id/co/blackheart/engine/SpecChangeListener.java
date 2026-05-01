package id.co.blackheart.engine;

import id.co.blackheart.service.strategy.StrategyExecutorFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Postgres LISTEN/NOTIFY consumer that drops cached spec-driven executors
 * whenever {@code strategy_definition} mutates (parametric blueprint M4.3).
 *
 * <p>The trigger {@code trg_notify_spec_change} (V20) emits one
 * {@code NOTIFY spec_change, '<strategy_code>'} per row mutation. This bean
 * holds a dedicated, long-lived JDBC connection — Postgres delivers
 * notifications to the connection that LISTENed, so we cannot share it with
 * the Hibernate pool. We open the LISTEN connection via {@link DriverManager}
 * directly so HikariCP's {@code maxLifetime} rotation never silently closes it
 * out from under us. A daemon thread polls
 * {@link PGConnection#getNotifications(int)} and, on each notification batch,
 * deduplicates by strategy code before invoking
 * {@link StrategyExecutorFactory#invalidateSpecCache(String)} so a bulk DDL
 * fan-out (one NOTIFY per row) collapses to one cache-bust per code.
 *
 * <p>Disabled by default (opt-in per environment) and excluded from the
 * research JVM — research never serves live ticks, so cache freshness is a
 * non-issue there. The poll loop is wrapped in a reconnect-with-backoff
 * watchdog: a transient SQL failure (network blip, DB restart) reconnects
 * with exponential backoff capped at one minute. Persistent failures keep
 * retrying — an operator restart is no longer required to recover.
 */
@Component
@Profile("!research")
@Slf4j
public class SpecChangeListener {

    private static final String CHANNEL = "spec_change";
    private static final int POLL_TIMEOUT_MS = 10_000;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;

    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final StrategyExecutorFactory executorFactory;
    private final boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile Connection conn;

    public SpecChangeListener(@Value("${spring.datasource.url}") String jdbcUrl,
                              @Value("${spring.datasource.username}") String jdbcUser,
                              @Value("${spring.datasource.password}") String jdbcPassword,
                              StrategyExecutorFactory executorFactory,
                              @Value("${engine.spec.hot-reload.enabled:false}") boolean enabled) {
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
        this.executorFactory = executorFactory;
        this.enabled = enabled;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("SpecChangeListener disabled — set engine.spec.hot-reload.enabled=true to opt in");
            return;
        }
        running.set(true);
        worker = new Thread(this::runWithReconnect, "spec-change-listener");
        worker.setDaemon(true);
        worker.start();
        log.info("SpecChangeListener started — listening on channel '{}'", CHANNEL);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (worker != null) worker.interrupt();
        closeQuietly(conn);
        conn = null;
    }

    private void runWithReconnect() {
        long backoff = INITIAL_BACKOFF_MS;
        while (running.get()) {
            try {
                openAndListen();
                backoff = INITIAL_BACKOFF_MS; // reset on a successful session
                pollLoop();
            } catch (SQLException ex) {
                if (!running.get()) return;
                log.warn("SpecChangeListener session ended ({}); reconnecting in {}ms",
                        ex.getMessage(), backoff);
            } catch (RuntimeException ex) {
                if (!running.get()) return;
                log.error("SpecChangeListener unexpected error; reconnecting in {}ms", backoff, ex);
            } finally {
                closeQuietly(conn);
                conn = null;
            }
            sleepQuietly(backoff);
            backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
        }
    }

    private void openAndListen() throws SQLException {
        // DriverManager bypasses HikariCP, so the LISTEN session is not
        // subject to maxLifetime rotation that would silently swallow notifies.
        Properties props = new Properties();
        props.setProperty("user", jdbcUser);
        props.setProperty("password", jdbcPassword);
        Connection c = DriverManager.getConnection(jdbcUrl, props);
        try (Statement st = c.createStatement()) {
            st.execute("LISTEN " + CHANNEL);
        }
        this.conn = c;
    }

    private void pollLoop() throws SQLException {
        PGConnection pg = conn.unwrap(PGConnection.class);
        while (running.get()) {
            PGNotification[] notifications = pg.getNotifications(POLL_TIMEOUT_MS);
            if (notifications == null || notifications.length == 0) continue;

            // Bulk DDL (e.g. archetype version bump touching N rows) fans out
            // N notifications. Dedup per batch so we invalidate each code once.
            Set<String> codes = new HashSet<>();
            boolean fullFlush = false;
            for (PGNotification n : notifications) {
                if (!CHANNEL.equals(n.getName())) continue;
                String payload = n.getParameter();
                if (payload == null || payload.isBlank()) {
                    fullFlush = true;
                } else {
                    codes.add(payload);
                }
            }
            if (fullFlush) {
                log.info("Received spec_change batch with empty payload — flushing entire spec cache");
                executorFactory.invalidateSpecCache();
            } else if (!codes.isEmpty()) {
                log.info("Received spec_change batch | size={} codes={}", notifications.length, codes);
                for (String code : codes) {
                    executorFactory.invalidateSpecCache(code);
                }
            }
        }
    }

    private static void closeQuietly(Connection c) {
        if (c == null) return;
        try {
            c.close();
        } catch (SQLException ignored) {
            // Closing on shutdown / reconnect — swallow.
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
