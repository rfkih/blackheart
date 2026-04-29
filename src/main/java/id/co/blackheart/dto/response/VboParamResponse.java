package id.co.blackheart.dto.response;

import id.co.blackheart.dto.vbo.VboParams;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class VboParamResponse {

    private UUID accountStrategyId;
    private boolean hasCustomParams;
    private Map<String, Object> overrides;
    private VboParams effectiveParams;
    private Long version;
    private LocalDateTime updatedAt;
}
