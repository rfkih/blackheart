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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Catalogue management for {@code strategy_definition} rows.
 *
 * <p>Any authenticated user may read (needed to populate strategy pickers);
 * only admins may create / update / soft-delete. The controller enforces
 * the admin rule via {@code @PreAuthorize}.
 *
 * <p>Every mutation appends a {@code strategy_definition_history} row in
 * the same transaction (see {@link StrategyDefinitionHistoryService}) — a
 * rolled-back mutation rolls back its history row too.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyDefinitionService {

    private static final String DEFAULT_STATUS = "ACTIVE";
    private static final String DEPRECATED_STATUS = "DEPRECATED";
    private static final String LEGACY_ARCHETYPE = "LEGACY_JAVA";
    /** Sort field name + Sortable whitelist key — matches the JPA property
     *  on {@code StrategyDefinition}. Kept as a constant so a future entity
     *  rename catches every site at compile time. */
    private static final String FIELD_STRATEGY_CODE = "strategyCode";
    /** Prefix for the "definition not found" exception message. The lookup +
     *  {@code orElseThrow} pattern repeats across read/update/deprecate, so
     *  a constant keeps the wording (and any future i18n) in one place. */
    private static final String NOT_FOUND_PREFIX = "Strategy definition not found: ";

    private final StrategyDefinitionRepository repository;
    private final StrategyDefinitionHistoryService historyService;
    private final StrategyExecutorFactory executorFactory;

    @Transactional(readOnly = true)
    public List<StrategyDefinitionResponse> list() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(StrategyDefinition::getStrategyCode))
                .map(this::toResponse)
                .toList();
    }

    /**
     * Filterable + paginated list. The query param does substring match on
     * both code and name. Sort field whitelist mirrors what the dashboard
     * panel exposes — anything else falls back to {@code strategyCode,asc} so
     * a malformed param can't drop the page into an undefined order.
     */
    @Transactional(readOnly = true)
    public Page<StrategyDefinitionResponse> listPaged(String query, String sort, int page, int size) {
        String normalized = StringUtils.hasText(query) ? query.trim() : null;
        int cappedSize = Math.clamp(size, 1, 100);
        Pageable pageable = PageRequest.of(Math.max(0, page), cappedSize, parseSort(sort));
        return repository.findFiltered(normalized, pageable).map(this::toResponse);
    }

    private static final Set<String> SORTABLE_FIELDS =
            Set.of(FIELD_STRATEGY_CODE, "strategyName", "strategyType", "archetype", "createdTime");

    private static Sort parseSort(String sort) {
        if (!StringUtils.hasText(sort)) {
            return Sort.by(Sort.Order.asc(FIELD_STRATEGY_CODE));
        }
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        if (!SORTABLE_FIELDS.contains(field)) {
            return Sort.by(Sort.Order.asc(FIELD_STRATEGY_CODE));
        }
        Sort.Direction dir = (parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()))
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return Sort.by(new Sort.Order(dir, field));
    }

    @Transactional(readOnly = true)
    public StrategyDefinitionResponse getById(UUID id) {
        return repository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_PREFIX + id));
    }

    @Transactional
    public StrategyDefinitionResponse create(CreateStrategyDefinitionRequest request, String actorEmail) {
        String code = request.getStrategyCode().trim();
        log.info("Creating strategy definition: code={} actor={}", code, actorEmail);

        repository.findByStrategyCode(code).ifPresent(existing -> {
            throw new UserAlreadyExistsException(
                    "Strategy code '" + code + "' is already registered");
        });

        String archetype = orDefault(request.getArchetype(), LEGACY_ARCHETYPE);
        Integer archetypeVersion = request.getArchetypeVersion() == null ? 1 : request.getArchetypeVersion();
        Integer specSchemaVersion = request.getSpecSchemaVersion() == null ? 1 : request.getSpecSchemaVersion();
        Map<String, Object> specJsonb = LEGACY_ARCHETYPE.equalsIgnoreCase(archetype)
                ? null
                : copyOrNull(request.getSpecJsonb());

        StrategyDefinition entity = StrategyDefinition.builder()
                .strategyDefinitionId(UUID.randomUUID())
                .strategyCode(code)
                .strategyName(request.getStrategyName().trim())
                .strategyType(request.getStrategyType().trim())
                .description(request.getDescription())
                .status(orDefault(request.getStatus(), DEFAULT_STATUS))
                .archetype(archetype)
                .archetypeVersion(archetypeVersion)
                .specJsonb(specJsonb)
                .specSchemaVersion(specSchemaVersion)
                .enabled(request.getEnabled() != null ? request.getEnabled() : false)
                .simulated(request.getSimulated() != null ? request.getSimulated() : true)
                .isDeleted(false)
                .build();
        entity.setCreatedBy(actorEmail);
        entity.setUpdatedBy(actorEmail);

        StrategyDefinition saved = repository.save(entity);
        historyService.recordChange(saved, StrategyDefinitionHistoryService.OP_INSERT, null,
                "Created by " + actorEmail);
        return toResponse(saved);
    }

    @Transactional
    public StrategyDefinitionResponse update(UUID id, UpdateStrategyDefinitionRequest request, String actorEmail) {
        log.info("Updating strategy definition: id={} actor={}", id, actorEmail);
        StrategyDefinition entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_PREFIX + id));

        boolean archetypeChanged = false;
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
        if (request.getArchetype() != null) {
            String next = request.getArchetype().trim();
            archetypeChanged = !next.equalsIgnoreCase(entity.getArchetype());
            entity.setArchetype(next);
        }
        if (request.getArchetypeVersion() != null) {
            entity.setArchetypeVersion(request.getArchetypeVersion());
        }
        if (request.getSpecJsonb() != null) {
            entity.setSpecJsonb(copyOrNull(request.getSpecJsonb()));
        }
        if (request.getSpecSchemaVersion() != null) {
            entity.setSpecSchemaVersion(request.getSpecSchemaVersion());
        }
        if (request.getEnabled() != null) {
            entity.setEnabled(request.getEnabled());
        }
        if (request.getSimulated() != null) {
            entity.setSimulated(request.getSimulated());
        }
        if (LEGACY_ARCHETYPE.equalsIgnoreCase(entity.getArchetype())) {
            // Legacy strategies must not carry a spec body — keep the column null
            // so engine readers never accidentally try to evaluate it.
            entity.setSpecJsonb(null);
        }
        entity.setUpdatedBy(actorEmail);

        StrategyDefinition saved = repository.save(entity);
        String operation = archetypeChanged
                ? StrategyDefinitionHistoryService.OP_UPGRADE
                : StrategyDefinitionHistoryService.OP_UPDATE;
        historyService.recordChange(saved, operation, null, request.getChangeReason());
        // Drop the cached spec-driven adapter so the next live tick rebuilds
        // from the fresh definition. Live executors keyed off this strategy
        // code would otherwise hold the old archetype/version/body until
        // the trading JVM restarts.
        executorFactory.invalidateSpecCache();
        return toResponse(saved);
    }

    /**
     * Soft-delete — flips status to DEPRECATED, sets {@code is_deleted=true},
     * and stamps {@code deleted_at}. Downstream tables (account_strategy,
     * backtest_run, *_param) reference {@code strategy_code} as free-form
     * strings; physically deleting the row would orphan references and break
     * historical detail pages.
     */
    @Transactional
    public StrategyDefinitionResponse deprecate(UUID id, String actorEmail) {
        log.info("Deprecating strategy definition: id={} actor={}", id, actorEmail);
        StrategyDefinition entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_PREFIX + id));
        entity.setStatus(DEPRECATED_STATUS);
        entity.setIsDeleted(true);
        entity.setDeletedAt(LocalDateTime.now());
        entity.setUpdatedBy(actorEmail);

        StrategyDefinition saved = repository.save(entity);
        historyService.recordChange(saved, StrategyDefinitionHistoryService.OP_DELETE, null,
                "Deprecated by " + actorEmail);
        executorFactory.invalidateSpecCache();
        return toResponse(saved);
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
                .archetype(s.getArchetype())
                .archetypeVersion(s.getArchetypeVersion())
                .specJsonb(s.getSpecJsonb() == null ? null : new HashMap<>(s.getSpecJsonb()))
                .specSchemaVersion(s.getSpecSchemaVersion())
                .enabled(s.getEnabled())
                .simulated(s.getSimulated())
                .isDeleted(s.getIsDeleted())
                .deletedAt(s.getDeletedAt())
                .createdTime(s.getCreatedTime())
                .updatedTime(s.getUpdatedTime())
                .build();
    }

    private String orDefault(String raw, String fallback) {
        if (raw == null) return fallback;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private Map<String, Object> copyOrNull(Map<String, Object> in) {
        return in == null ? null : new HashMap<>(in);
    }
}
