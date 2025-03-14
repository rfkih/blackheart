package id.co.blackheart.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class PredictionResponse {
    private String signal;
    private BigDecimal confidence;
    private String pair;
    private String model;
}
