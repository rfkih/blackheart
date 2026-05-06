package id.co.blackheart.service.technicalindicator.patcher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Auto-wires {@link FeaturePatcher} beans into a column-name-keyed map at
 * startup. The registry is the single source of truth for "what columns are
 * patchable" — the unified UI calls {@link #registeredColumns()} to render
 * the dynamic list of repair actions.
 *
 * <p>Defensively rejects column identifiers that don't match
 * {@code [a-z_][a-z0-9_]*} so the framework can safely interpolate them
 * into native SQL when finding NULL rows.
 */
@Slf4j
@Component
public class FeaturePatcherRegistry {

    private static final Pattern SAFE_IDENT = Pattern.compile("[a-z_][a-z0-9_]*");

    private final Map<String, FeaturePatcher<?>> patchers;

    public FeaturePatcherRegistry(List<FeaturePatcher<?>> beans) {
        Map<String, FeaturePatcher<?>> map = new HashMap<>();
        for (FeaturePatcher<?> p : beans) {
            String column = p.primaryColumn();
            if (column == null || !SAFE_IDENT.matcher(column).matches()) {
                throw new IllegalStateException(
                        "FeaturePatcher " + p.getClass().getName()
                                + " has invalid primaryColumn=" + column
                                + " (must match [a-z_][a-z0-9_]*)");
            }
            FeaturePatcher<?> previous = map.put(column, p);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate FeaturePatcher for column=" + column
                                + ": " + previous.getClass().getName()
                                + " and " + p.getClass().getName());
            }
        }
        this.patchers = Map.copyOf(map);
        log.info("FeaturePatcherRegistry initialized with {} patcher(s): {}",
                patchers.size(), patchers.keySet());
    }

    public Optional<FeaturePatcher<?>> find(String column) {
        if (column == null) return Optional.empty();
        return Optional.ofNullable(patchers.get(column));
    }

    public Set<String> registeredColumns() {
        return Collections.unmodifiableSet(patchers.keySet());
    }
}
