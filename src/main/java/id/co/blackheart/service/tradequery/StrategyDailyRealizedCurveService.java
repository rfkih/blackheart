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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.ZoneOffset;
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

    /**
     * Self-reference injected lazily to route internal calls through the Spring proxy.
     * Required so that @Transactional on generateForDate is honoured when called from rebuildRange.
     * Without this, self-invocation bypasses the proxy and all days share one giant transaction.
     */
    @Lazy
    @Autowired
    private StrategyDailyRealizedCurveService self;

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Jakarta");

    @Transactional
    public void generateForYesterday() {
        LocalDate curveDate = LocalDate.now(DEFAULT_ZONE).minusDays(1);
        generateForDate(curveDate);
    }

    @Transactional
    public void generateForDate(LocalDate curveDate) {
        LocalDateTime startDateTime = curveDate.atStartOfDay(DEFAULT_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime endDateTime = curveDate.plusDays(1).atStartOfDay(DEFAULT_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        log.info("Generating strategy daily realized curve for date={}", curveDate);

        List<EnabledAccountStrategyProjection> enabledStrategies = accountStrategyRepository.findAllEnabledStrategyRefs();

        List<UUID> strategyIds = enabledStrategies.stream()
                .map(EnabledAccountStrategyProjection::getAccountStrategyId)
                .toList();

        List<TradePositionDailyAggregateProjection> aggregateProjections = tradePositionRepository.findDailyClosedPositionAggregates(startDateTime, endDateTime);

        Map<UUID, DailyPositionAggregateDto> aggregateByStrategyId = aggregateProjections.stream()
                .map(this::toDto)
                .collect(Collectors.toMap(
                        DailyPositionAggregateDto::getAccountStrategyId,
                        Function.identity(),
                        (existing, duplicate) -> {
                            log.warn("Duplicate aggregate for accountStrategyId={} — keeping first",
                                    existing.getAccountStrategyId());
                            return existing;
                        },
                        LinkedHashMap::new
                ));

        // Batch-fetch previous and current curves in 2 queries instead of N×2.
        Map<UUID, StrategyDailyRealizedCurve> previousCurveByStrategyId =
                strategyDailyRealizedCurveRepository
                        .findLatestBeforeDateForStrategies(strategyIds, curveDate)
                        .stream()
                        .collect(Collectors.toMap(StrategyDailyRealizedCurve::getAccountStrategyId, c -> c));

        Map<UUID, StrategyDailyRealizedCurve> currentCurveByStrategyId =
                strategyDailyRealizedCurveRepository
                        .findByAccountStrategyIdsAndCurveDate(strategyIds, curveDate)
                        .stream()
                        .collect(Collectors.toMap(StrategyDailyRealizedCurve::getAccountStrategyId, c -> c));

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
                            .breakevenPositionCount(0)
                            .build()
            );

            StrategyDailyRealizedCurve previousCurve = previousCurveByStrategyId.get(accountStrategyId);
            StrategyDailyRealizedCurve currentCurve  = currentCurveByStrategyId.get(accountStrategyId);

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

    // No @Transactional here — each day is committed independently via self.generateForDate().
    // This means a failure on day N does not roll back days 1..N-1.
    public void rebuildRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate must not be null");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be greater than or equal to startDate");
        }

        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            // Call through the Spring proxy so @Transactional on generateForDate is honoured.
            self.generateForDate(current);
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
                .breakevenPositionCount(projection.getBreakevenPositionCount())
                .build();
    }
}
