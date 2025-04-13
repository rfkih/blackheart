package id.co.blackheart.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TrainRequest {
    private String symbol;
    private String interval;
    private int totalData;
    private double thresholdUp;
    private double thresholdDown;
}
