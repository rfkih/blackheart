package id.co.blackheart;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pinned explicitly to {@link BlackheartApplication}. With two
 * {@code @SpringBootApplication} classes in the same package
 * (the trading and research entry points), Spring's auto-discovery
 * for {@code @SpringBootTest} is undefined — pinning prevents the test
 * from accidentally booting under {@link BlackheartResearchApplication},
 * which would skip the profile-forcing main() and could register live
 * trading singletons in test context.
 */
@SpringBootTest(classes = BlackheartApplication.class)
class BlackheartApplicationTests {

	@Test
	void contextLoads() {
	}

}
