package id.co.blackheart;

import id.co.blackheart.config.RuntimeHintsConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Trading-service entry point. Profile-gated so this configuration class
 * does NOT register on the research-isolated JVM (which runs
 * {@link BlackheartResearchApplication} as its main class with the
 * "research" profile activated). Without this gate, both main classes
 * are picked up by component scan in either JVM and Spring fails with
 * duplicate {@code @EnableJpaRepositories} bean registrations.
 */
@SpringBootApplication
@Profile("!research")
@EnableScheduling
@ImportRuntimeHints(RuntimeHintsConfig.class)
@EnableJpaRepositories(basePackages = "id.co.blackheart.repository")
@EntityScan(basePackages = "id.co.blackheart.model")
public class BlackheartApplication {

	public static void main(String[] args) {
		SpringApplication.run(BlackheartApplication.class, args);
	}

}
