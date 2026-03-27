package id.co.blackheart.dto.response;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LatestPriceResponse {
    private String symbol;
    private BigDecimal price;
    private LocalDateTime updatedAt;
}