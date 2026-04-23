package id.co.blackheart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * General-purpose async executor. The old 5/10/100 shape starved under
     * concurrent load — live trading loops + backfills + on-demand Monte Carlo
     * runs all share this pool. With {@code CallerRunsPolicy} a full queue now
     * throttles the submitter instead of silently aborting tasks, which used to
     * drop backfill batches on the floor.
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("Async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated pool for long-running backtest executions. Isolated from
     * {@code taskExecutor} because a multi-year 1m backtest can run for
     * minutes and would starve the general-purpose pool (which also carries
     * live PnL publish fan-out and backfill work).
     *
     * <p>Bounded queue + {@code AbortPolicy}: rather than quietly running a
     * rejected submission on the caller's thread (which would re-block the
     * HTTP handler — exactly what we're trying to avoid), we surface the
     * overload as an error so the API returns a friendly 429-shaped response.
     */
    @Bean(name = "backtestExecutor")
    public Executor backtestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("Backtest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}

