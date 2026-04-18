package id.co.blackheart.dto.response;

import id.co.blackheart.dto.vcb.VcbParams;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class VcbParamResponse {

    private UUID accountStrategyId;
    private boolean hasCustomParams;
    private Map<String, Object> overrides;
    private VcbParams effectiveParams;
    private Long version;
    private LocalDateTime updatedAt;
}
