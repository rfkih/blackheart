package id.co.blackheart.config;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer configuration for the backtest queue. Research-JVM only —
 * the trading JVM does not run a consumer and must not start backtest
 * execution threads.
 *
 * <p>Concurrency matches the research-agent slot limit (3) so all queued
 * slots for the research agent can execute in parallel. The topic has 3
 * partitions — each consumer thread owns one partition.
 */
@Configuration
@Profile("research")
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${app.backtest.consumer-concurrency:3}")
    private int consumerConcurrency;

    @Bean
    public ConsumerFactory<String, String> backtestConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "backtest-consumer-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Backtest jobs block the listener thread for the full run duration.
        // Default max.poll.interval.ms is 5 min — any backtest longer than that
        // causes the broker to kick the consumer out before ack.acknowledge()
        // fires, producing CommitFailedException. Set to 2 hours to cover the
        // worst-case sweep. max.poll.records=1 ensures one job per poll cycle
        // so the interval timer only ticks during actual processing, not queuing.
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 7_200_000);  // 2 h
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        // session.timeout / heartbeat — Spring Kafka's background heartbeat thread
        // keeps the session alive; these just need to be consistent with each other.
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 60_000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 20_000);
        // Slow down retry spam when broker is unreachable (default max is 1 s → floods logs).
        props.put(CommonClientConfigs.RECONNECT_BACKOFF_MS_CONFIG, 2_000);
        props.put(CommonClientConfigs.RECONNECT_BACKOFF_MAX_MS_CONFIG, 30_000);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> backtestKafkaListenerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(backtestConsumerFactory());
        factory.setConcurrency(consumerConcurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    /**
     * Explicit KafkaAdmin overrides Spring Boot's auto-configured one so that
     * a missing broker at startup is logged instead of crashing the JVM.
     * No NewTopic beans are registered here — the topic auto-creates with the
     * correct partition count via KAFKA_CFG_NUM_PARTITIONS in docker-compose.
     */
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        KafkaAdmin admin = new KafkaAdmin(configs);
        admin.setFatalIfBrokerNotAvailable(false);
        admin.setOperationTimeout(5);
        return admin;
    }

}
