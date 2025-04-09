package id.co.blackheart.dto.request;


import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PredictionRequest {
    private String symbol;  // Initial Account
    private String interval;     // Amount to be risked at a trade
    private boolean isStock;
}
