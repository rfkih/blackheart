package id.co.blackheart.service.pnl;

import id.co.blackheart.dto.response.DailyPnlResponse;
import id.co.blackheart.dto.response.PnlSummaryResponse;
import id.co.blackheart.dto.response.StrategyPnlResponse;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PnlService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TradesRepository tradesRepository;
    private final AccountRepository accountRepository;
    private final CacheService cacheService;

    public PnlSummaryResponse getSummary(UUID userId, String period) {
        List<UUID> accountIds = getAccountIds(userId);
        LocalDateTime[] range = periodRange(period);
        LocalDateTime from = range[0];
        LocalDateTime to = range[1];

        List<Trades> closed = tradesRepository.findClosedInPeriodByAccountIds(accountIds, from, to);
        List<Trades> open = tradesRepository.findOpenByAccountIds(accountIds);

        BigDecimal realizedPnl = closed.stream()
                .map(t -> t.getRealizedPnlAmount() != null ? t.getRealizedPnlAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long wins = closed.stream()
                .filter(t -> t.getRealizedPnlAmount() != null && t.getRealizedPnlAmount().compareTo(BigDecimal.ZERO) > 0)
                .count();

        BigDecimal winRate = closed.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(closed.size()), 4, RoundingMode.HALF_UP).multiply(HUNDRED);

        BigDecimal unrealizedPnl = computeUnrealizedPnl(open);
        BigDecimal totalPnl = realizedPnl.add(unrealizedPnl);

        return PnlSummaryResponse.builder()
                .period(period)
                .realizedPnl(realizedPnl.setScale(8, RoundingMode.HALF_UP))
                .unrealizedPnl(unrealizedPnl.setScale(8, RoundingMode.HALF_UP))
                .totalPnl(totalPnl.setScale(8, RoundingMode.HALF_UP))
                .tradeCount(closed.size())
                .winRate(winRate)
                .openCount(open.size())
                .build();
    }

    public List<DailyPnlResponse> getDaily(UUID userId, LocalDate from, LocalDate to, String strategyCode) {
        List<UUID> accountIds = getAccountIds(userId);
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);

        List<Trades> closed = tradesRepository.findClosedInPeriodByAccountIds(accountIds, fromDt, toDt);

        if (strategyCode != null && !strategyCode.isBlank()) {
            closed = closed.stream()
                    .filter(t -> strategyCode.equalsIgnoreCase(t.getStrategyName()))
                    .collect(Collectors.toList());
        }

        Map<String, List<Trades>> byDate = new TreeMap<>();
        for (Trades t : closed) {
            String dateKey = t.getExitTime().toLocalDate().format(DATE_FMT);
            byDate.computeIfAbsent(dateKey, k -> new ArrayList<>()).add(t);
        }

        return byDate.entrySet().stream().map(e -> {
            BigDecimal pnl = e.getValue().stream()
                    .map(t -> t.getRealizedPnlAmount() != null ? t.getRealizedPnlAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return DailyPnlResponse.builder()
                    .date(e.getKey())
                    .realizedPnl(pnl.setScale(8, RoundingMode.HALF_UP))
                    .tradeCount(e.getValue().size())
                    .build();
        }).collect(Collectors.toList());
    }

    public List<StrategyPnlResponse> getByStrategy(UUID userId, LocalDate from, LocalDate to) {
        List<UUID> accountIds = getAccountIds(userId);
        LocalDateTime fromDt = (from != null) ? from.atStartOfDay() : LocalDateTime.of(2020, 1, 1, 0, 0);
        LocalDateTime toDt = (to != null) ? to.atTime(LocalTime.MAX) : LocalDateTime.now(ZoneOffset.UTC);

        List<Trades> closed = tradesRepository.findClosedInPeriodByAccountIds(accountIds, fromDt, toDt);

        Map<String, List<Trades>> byStrategy = new TreeMap<>();
        for (Trades t : closed) {
            String code = t.getStrategyName() != null ? t.getStrategyName() : "UNKNOWN";
            byStrategy.computeIfAbsent(code, k -> new ArrayList<>()).add(t);
        }

        return byStrategy.entrySet().stream().map(e -> {
            List<Trades> list = e.getValue();
            BigDecimal pnl = list.stream()
                    .map(t -> t.getRealizedPnlAmount() != null ? t.getRealizedPnlAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long wins = list.stream()
                    .filter(t -> t.getRealizedPnlAmount() != null && t.getRealizedPnlAmount().compareTo(BigDecimal.ZERO) > 0)
                    .count();
            BigDecimal winRate = list.isEmpty() ? BigDecimal.ZERO
                    : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(list.size()), 4, RoundingMode.HALF_UP).multiply(HUNDRED);
            return StrategyPnlResponse.builder()
                    .strategyCode(e.getKey())
                    .realizedPnl(pnl.setScale(8, RoundingMode.HALF_UP))
                    .tradeCount(list.size())
                    .winRate(winRate)
                    .build();
        }).collect(Collectors.toList());
    }

    private BigDecimal computeUnrealizedPnl(List<Trades> openTrades) {
        BigDecimal total = BigDecimal.ZERO;
        for (Trades t : openTrades) {
            try {
                BigDecimal price = cacheService.getLatestPrice(t.getAsset());
                if (price == null || t.getAvgEntryPrice() == null || t.getTotalRemainingQty() == null) continue;
                BigDecimal pnl;
                if ("SHORT".equalsIgnoreCase(t.getSide())) {
                    pnl = t.getAvgEntryPrice().subtract(price).multiply(t.getTotalRemainingQty());
                } else {
                    pnl = price.subtract(t.getAvgEntryPrice()).multiply(t.getTotalRemainingQty());
                }
                total = total.add(pnl);
            } catch (Exception e) {
                log.warn("Could not compute unrealized pnl for trade {}", t.getTradeId());
            }
        }
        return total;
    }

    private List<UUID> getAccountIds(UUID userId) {
        return accountRepository.findByUserId(userId).stream()
                .map(Account::getAccountId)
                .collect(Collectors.toList());
    }

    private LocalDateTime[] periodRange(String period) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime from;
        switch (period.toLowerCase()) {
            case "week":
                from = now.toLocalDate().atStartOfDay().minusDays(now.getDayOfWeek().getValue() - 1);
                break;
            case "month":
                from = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
                break;
            default:
                from = now.toLocalDate().atStartOfDay();
        }
        return new LocalDateTime[]{from, now};
    }
}
