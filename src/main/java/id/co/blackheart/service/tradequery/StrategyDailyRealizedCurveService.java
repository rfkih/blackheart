package id.co.blackheart.service.tradequery;

import id.co.blackheart.dto.DailyPositionAggregateDto;
import id.co.blackheart.model.StrategyDailyRealizedCurve;
import id.co.blackheart.projection.EnabledAccountStrategyProjection;
import id.co.blackheart.projection.TradePositionDailyAggregateProjection;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.StrategyDailyRealizedCurveRepository;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.util.StrategyDailyRealizedCurveCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyDailyRealizedCurveService {

    private final TradePositionRepository tradePositionRepository;
    private final StrategyDailyRealizedCurveRepository strategyDailyRealizedCurveRepository;
    private final AccountStrategyRepository accountStrategyRepository;
    private final StrategyDailyRealizedCurveCalculator calculator;

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Jakarta");

    @Transactional
    public void generateForYesterday() {
        LocalDate curveDate = LocalDate.now(DEFAULT_ZONE).minusDays(1);
        generateForDate(curveDate);
    }

    @Transactional
    public void generateForDate(LocalDate curveDate) {
        LocalDateTime startDateTime = curveDate.atStartOfDay();
        LocalDateTime endDateTime = curveDate.plusDays(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now(DEFAULT_ZONE);

        log.info("Generating strategy daily realized curve for date={}", curveDate);

        List<EnabledAccountStrategyProjection> enabledStrategies = accountStrategyRepository.findAllEnabledStrategyRefs();

        List<TradePositionDailyAggregateProjection> aggregateProjections =
                tradePositionRepository.findDailyClosedPositionAggregates(startDateTime, endDateTime);

        Map<UUID, DailyPositionAggregateDto> aggregateByStrategyId = aggregateProjections.stream()
                .map(this::toDto)
                .collect(Collectors.toMap(
                        DailyPositionAggregateDto::getAccountStrategyId,
                        Function.identity(),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        List<StrategyDailyRealizedCurve> rowsToSave = new ArrayList<>();

        for (EnabledAccountStrategyProjection enabledStrategy : enabledStrategies) {
            UUID accountId = enabledStrategy.getAccountId();
            UUID accountStrategyId = enabledStrategy.getAccountStrategyId();

            DailyPositionAggregateDto aggregate = aggregateByStrategyId.getOrDefault(
                    accountStrategyId,
                    DailyPositionAggregateDto.builder()
                            .accountId(accountId)
                            .accountStrategyId(accountStrategyId)
                            .dailyRealizedPnlAmount(BigDecimal.ZERO)
                            .dailyClosedNotional(BigDecimal.ZERO)
                            .closedPositionCount(0)
                            .winPositionCount(0)
                            .lossPositionCount(0)
                            .build()
            );

            StrategyDailyRealizedCurve previousCurve =
                    strategyDailyRealizedCurveRepository
                            .findTopByAccountStrategyIdAndCurveDateBeforeOrderByCurveDateDesc(accountStrategyId, curveDate)
                            .orElse(null);

            StrategyDailyRealizedCurve currentCurve =
                    strategyDailyRealizedCurveRepository
                            .findByAccountStrategyIdAndCurveDate(accountStrategyId, curveDate)
                            .orElse(null);

            StrategyDailyRealizedCurve calculatedCurve = calculator.calculate(
                    UUID.randomUUID(),
                    curveDate,
                    previousCurve,
                    currentCurve,
                    aggregate,
                    now
            );

            rowsToSave.add(calculatedCurve);
        }

        strategyDailyRealizedCurveRepository.saveAll(rowsToSave);

        log.info("Finished generating strategy daily realized curve for date={}, totalRows={}",
                curveDate, rowsToSave.size());
    }

    @Transactional
    public void rebuildRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate must not be null");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be greater than or equal to startDate");
        }

        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            generateForDate(current);
            current = current.plusDays(1);
        }
    }

    private DailyPositionAggregateDto toDto(TradePositionDailyAggregateProjection projection) {
        return DailyPositionAggregateDto.builder()
                .accountId(projection.getAccountId())
                .accountStrategyId(projection.getAccountStrategyId())
                .dailyRealizedPnlAmount(projection.getDailyRealizedPnlAmount())
                .dailyClosedNotional(projection.getDailyClosedNotional())
                .closedPositionCount(projection.getClosedPositionCount())
                .winPositionCount(projection.getWinPositionCount())
                .lossPositionCount(projection.getLossPositionCount())
                .build();
    }
}
