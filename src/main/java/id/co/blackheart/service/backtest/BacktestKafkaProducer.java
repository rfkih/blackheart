package id.co.blackheart.service.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends a backtest run ID to the {@code backtest-requests} Kafka topic.
 * The userId is used as the message key so submissions from the same user
 * land on the same partition (advisory ordering).
 *
 * <p>{@link #send} blocks until the broker acknowledges the message (up to
 * {@code SEND_TIMEOUT_SECONDS}). This makes broker failures synchronous and
 * catchable by the caller — without blocking, {@code kafkaTemplate.send()}
 * returns a future that is discarded, and any broker error fires on a
 * background thread where it can't trigger the HTTP 503 fallback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestKafkaProducer {

    private static final int SEND_TIMEOUT_SECONDS = 6;

    @Value("${app.backtest.kafka.topic:backtest-requests}")
    private String topic;

    private final KafkaTemplate<String, String> kafkaTemplate;

    public void send(UUID backtestRunId, UUID userId) {
        String key = userId != null ? userId.toString() : backtestRunId.toString();
        try {
            kafkaTemplate.send(topic, key, backtestRunId.toString())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new RuntimeException("Kafka produce failed: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new RuntimeException("Kafka produce timed out after " + SEND_TIMEOUT_SECONDS + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while sending to Kafka", e);
        }
        log.info("Backtest queued | topic={} runId={} userId={}", topic, backtestRunId, userId);
    }
}
