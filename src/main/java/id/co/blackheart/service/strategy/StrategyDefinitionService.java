package id.co.blackheart.service.strategy;

import id.co.blackheart.dto.request.CreateStrategyDefinitionRequest;
import id.co.blackheart.dto.request.UpdateStrategyDefinitionRequest;
import id.co.blackheart.dto.response.StrategyDefinitionResponse;
import id.co.blackheart.exception.UserAlreadyExistsException;
import id.co.blackheart.model.StrategyDefinition;
import id.co.blackheart.repository.StrategyDefinitionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Catalogue management for {@code strategy_definition} rows.
 *
 * <p>Any authenticated user may read (needed to populate strategy pickers);
 * only admins may create / update / soft-delete. The controller enforces
 * the admin rule via {@code @PreAuthorize}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyDefinitionService {

    private final StrategyDefinitionRepository repository;

    private static final String DEFAULT_STATUS = "ACTIVE";
    private static final String DEPRECATED_STATUS = "DEPRECATED";

    @Transactional(readOnly = true)
    public List<StrategyDefinitionResponse> list() {
        return repository.findAll().stream()
                // Stable ordering — code is unique and immutable, so it makes a
                // predictable primary sort key for the admin table.
                .sorted(Comparator.comparing(StrategyDefinition::getStrategyCode))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public StrategyDefinitionResponse getById(UUID id) {
        return repository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new EntityNotFoundException("Strategy definition not found: " + id));
    }

    @Transactional
    public StrategyDefinitionResponse create(CreateStrategyDefinitionRequest request, String actorEmail) {
        String code = request.getStrategyCode().trim();
        log.info("Creating strategy definition: code={} actor={}", code, actorEmail);

        repository.findByStrategyCode(code).ifPresent(existing -> {
            // Reuses the "already exists" exception family — GlobalExceptionHandler
            // maps it to 409, keeping the client error shape consistent with
            // user + account collisions.
            throw new UserAlreadyExistsException(
                    "Strategy code '" + code + "' is already registered");
        });

        StrategyDefinition entity = StrategyDefinition.builder()
                .strategyDefinitionId(UUID.randomUUID())
                .strategyCode(code)
                .strategyName(request.getStrategyName().trim())
                .strategyType(request.getStrategyType().trim())
                .description(request.getDescription())
                .status(orDefault(request.getStatus(), DEFAULT_STATUS))
                .build();
        entity.setCreatedBy(actorEmail);
        entity.setUpdatedBy(actorEmail);

        StrategyDefinition saved = repository.save(entity);
        return toResponse(saved);
    }

    @Transactional
    public StrategyDefinitionResponse update(UUID id, UpdateStrategyDefinitionRequest request, String actorEmail) {
        log.info("Updating strategy definition: id={} actor={}", id, actorEmail);
        StrategyDefinition entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Strategy definition not found: " + id));

        // Partial update — only apply fields that were actually sent.
        // strategyCode is intentionally omitted: it's the stable key.
        if (request.getStrategyName() != null) {
            entity.setStrategyName(request.getStrategyName().trim());
        }
        if (request.getStrategyType() != null) {
            entity.setStrategyType(request.getStrategyType().trim());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus().trim());
        }
        entity.setUpdatedBy(actorEmail);

        return toResponse(repository.save(entity));
    }

    /**
     * Soft-delete — flips status to DEPRECATED instead of removing the row.
     * Downstream tables (account_strategy, backtest_run, *_param) store
     * strategy_code as free-form strings; physically deleting the row would
     * orphan references and break historical detail pages.
     */
    @Transactional
    public StrategyDefinitionResponse deprecate(UUID id, String actorEmail) {
        log.info("Deprecating strategy definition: id={} actor={}", id, actorEmail);
        StrategyDefinition entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Strategy definition not found: " + id));
        entity.setStatus(DEPRECATED_STATUS);
        entity.setUpdatedBy(actorEmail);
        return toResponse(repository.save(entity));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private StrategyDefinitionResponse toResponse(StrategyDefinition s) {
        return StrategyDefinitionResponse.builder()
                .strategyDefinitionId(s.getStrategyDefinitionId())
                .strategyCode(s.getStrategyCode())
                .strategyName(s.getStrategyName())
                .strategyType(s.getStrategyType())
                .description(s.getDescription())
                .status(s.getStatus())
                .createdTime(s.getCreatedTime())
                .updatedTime(s.getUpdatedTime())
                .build();
    }

    private String orDefault(String raw, String fallback) {
        if (raw == null) return fallback;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
