package id.co.blackheart.logging;

import id.co.blackheart.service.observability.ErrorIngestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Connects Spring's {@link ErrorIngestService} bean to the static delegate on
 * {@link DbErrorAppender}. Logback instantiates appenders during XML config
 * parse, which happens before Spring starts; this bridge fires after the
 * context is up so the appender's worker can begin draining its queue
 * through the service.
 */
@Component
@Slf4j
public class LogbackBridge {

    private final ErrorIngestService ingestService;

    public LogbackBridge(ErrorIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void wireAppender() {
        DbErrorAppender.setIngestService(ingestService);
        log.info("DbErrorAppender wired to ErrorIngestService");
    }
}
