package id.co.blackheart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the research-isolated JVM. Companion to
 * {@link BlackheartApplication}, which is the trading-JVM entry point.
 *
 * <p><b>Why a separate main class?</b> The {@code researchBootJar}
 * Gradle task uses this class as the {@code Main-Class} manifest entry
 * so that the research JAR has a distinct boot path. Today both JARs
 * contain the same class files; the {@code @Profile("!research")}
 * gates added in Stage A prevent live-trading singletons from
 * registering on this JVM. In Stage B2 (future module split), the
 * research JAR will physically exclude live-trading code, and this
 * class becomes the natural home for any research-specific
 * boot-time configuration.
 *
 * <p><b>Auto-activated profile.</b> Setting
 * {@code spring.profiles.active=research} on the JVM args is what
 * actually flips the gating; this main class does NOT force the
 * profile (operators can still override). For convenience, the
 * companion {@code research/scripts/run-research-service.sh} sets
 * {@code spring.profiles.active=dev,research} by default.
 *
 * <p><b>EnableScheduling notes.</b> Even with {@code @EnableScheduling}
 * here, {@code @Scheduled} methods on gated beans (LivePnlPublisherService,
 * SentimentPublisherService, TelegramBotPollingService) do not fire on
 * this JVM because those beans don't register. {@code @EnableScheduling}
 * itself is harmless to retain — it just enables Spring's scheduler
 * infrastructure for any future research-side scheduled work.
 *
 * <p>See {@code research/DEPLOYMENT.md} step 3 for the full two-JVM
 * operating model.
 */
@SpringBootApplication
@Profile("research")
@EnableScheduling
@EnableJpaRepositories(basePackages = "id.co.blackheart.repository")
@EntityScan(basePackages = "id.co.blackheart.model")
public class BlackheartResearchApplication {

    public static void main(String[] args) {
        // Force the `research` profile programmatically. setAdditionalProfiles
        // is additive — operators can still pass extra profiles via
        // --spring.profiles.active=prod (which becomes prod,research) or
        // SPRING_PROFILES_ACTIVE env var. They CANNOT, however, boot this
        // main class WITHOUT the research profile, which is the safety
        // property we need.
        //
        // Why this matters: @Profile("!research") gates the live-trading
        // singletons (BinanceWebSocketClient, schedulers, etc.). If this
        // JAR booted with only a default profile, those singletons would
        // register on what was supposed to be the research-isolated JVM,
        // and a second Binance WebSocket connection would compete with
        // the trading service — risk of double-trading.
        //
        // (Bug 2 fix, identified 2026-04-28.)
        SpringApplication app = new SpringApplication(BlackheartResearchApplication.class);
        app.setAdditionalProfiles("research");
        app.run(args);
    }
}
