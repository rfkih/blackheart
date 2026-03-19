package id.co.blackheart.dto.tradelistener;

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
public class ListenerDecision {

    private boolean triggered;
    private String exitReason;
    private BigDecimal exitPrice;
    private LocalDateTime exitTime;

    public static ListenerDecision none() {
        return ListenerDecision.builder()
                .triggered(false)
                .build();
    }
}
