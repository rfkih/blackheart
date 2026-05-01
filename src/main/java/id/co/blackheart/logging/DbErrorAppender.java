package id.co.blackheart.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import id.co.blackheart.service.observability.ErrorEvent;
import id.co.blackheart.service.observability.ErrorIngestService;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges Logback ERROR events to {@link ErrorIngestService}. Logback
 * instantiates appenders BEFORE the Spring context exists, so this class
 * holds a static, volatile reference to the ingest service that
 * {@link LogbackBridge} sets once the application context is ready. Until
 * the bridge fires we buffer events in a bounded queue; once it fires we
 * drain the queue and stream new events through.
 *
 * <p>Asynchrony: a single dedicated worker thread polls the queue and calls
 * {@code ingest.ingest(event)} (which is itself {@code @Async}, so this
 * thread never blocks on DB or Telegram). The worker is started lazily on
 * the first call to {@link #append(ILoggingEvent)} so it costs nothing
 * during early bootstrap.
 *
 * <p>Overflow policy: bounded queue, drop newest on full and increment a
 * counter. Dropping is preferable to blocking because the appender is in
 * the path of every ERROR log call — blocking would back-pressure the JVM.
 */
public class DbErrorAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static volatile ErrorIngestService INGEST;

    private static final int QUEUE_CAPACITY = 1000;
    private static final int STACK_FRAME_LIMIT = 5;
    private static final int STACK_TRACE_CHAR_LIMIT = 16_000;

    private final BlockingQueue<ErrorEvent> buffer = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private volatile Thread worker;
    private volatile boolean running;

    /**
     * Called by {@link LogbackBridge} after Spring is up. Until this fires,
     * the queue accumulates ErrorEvents that the worker will drain once the
     * service appears.
     */
    public static void setIngestService(ErrorIngestService service) {
        INGEST = service;
    }

    @Override
    public void start() {
        super.start();
        startWorker();
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) return;
        if (worker == null) startWorker();
        ErrorEvent built = build(event);
        if (built == null) return;
        if (!buffer.offer(built)) {
            long n = dropped.incrementAndGet();
            // Guarded warn so an overflowing queue doesn't itself spam.
            if (n == 1 || n % 100 == 0) {
                addWarn("DbErrorAppender queue full — dropped " + n + " events total");
            }
        }
    }

    private synchronized void startWorker() {
        if (worker != null) return;
        running = true;
        worker = new Thread(this::runLoop, "ErrorAppender");
        worker.setDaemon(true);
        worker.start();
    }

    private void runLoop() {
        while (running) {
            ErrorEvent ev;
            try {
                ev = buffer.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            ErrorIngestService svc = INGEST;
            if (svc == null) {
                // Spring not up yet — re-queue and wait. Bootstrap-only path.
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                buffer.offer(ev);
                continue;
            }
            try {
                svc.ingest(ev);
            } catch (Throwable t) {
                addWarn("ingest failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }
    }

    private ErrorEvent build(ILoggingEvent event) {
        IThrowableProxy tp = event.getThrowableProxy();
        String exClass = tp == null ? null : tp.getClassName();
        String stack = tp == null ? null : truncate(ThrowableProxyUtil.asString(tp), STACK_TRACE_CHAR_LIMIT);
        String fp = fingerprint(event.getLoggerName(), exClass, tp);

        Map<String, String> mdc = event.getMDCPropertyMap() == null
                ? Map.of()
                : new HashMap<>(event.getMDCPropertyMap());

        LocalDateTime occurred = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault());

        return new ErrorEvent(
                occurred,
                event.getLoggerName(),
                event.getThreadName(),
                event.getLevel().toString(),
                event.getFormattedMessage(),
                exClass,
                stack,
                mdc,
                fp,
                // jvm = null → ErrorIngestService falls back to blackheart.jvm.name
                // (trading or research). The appender does not know the value
                // because it is instantiated before the Spring context.
                null
        );
    }

    private static String fingerprint(String loggerName, String exClass, IThrowableProxy tp) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(loggerName == null ? "?" : loggerName).append('|');
        sb.append(exClass == null ? "?" : exClass).append('|');
        if (tp != null && tp.getStackTraceElementProxyArray() != null) {
            StackTraceElementProxy[] frames = tp.getStackTraceElementProxyArray();
            int limit = Math.min(STACK_FRAME_LIMIT, frames.length);
            for (int i = 0; i < limit; i++) {
                StackTraceElement e = frames[i].getStackTraceElement();
                sb.append(e.getClassName()).append('#').append(e.getMethodName()).append(';');
            }
        }
        return sha256Hex(sb.toString());
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(h.length * 2);
            for (byte b : h) {
                String x = Integer.toHexString(b & 0xff);
                if (x.length() == 1) out.append('0');
                out.append(x);
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "\n...[truncated]";
    }
}
