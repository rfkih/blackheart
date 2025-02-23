package id.co.blackheart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "id.co.blackheart.repository")
@EntityScan(basePackages = "id.co.blackheart.model")
public class BlackheartApplication {

	public static void main(String[] args) {
		SpringApplication.run(BlackheartApplication.class, args);
	}

}
