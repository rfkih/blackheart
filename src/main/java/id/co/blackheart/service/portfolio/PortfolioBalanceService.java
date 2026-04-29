package id.co.blackheart.service.portfolio;

import id.co.blackheart.dto.response.PortfolioAssetResponse;
import id.co.blackheart.dto.response.PortfolioBalanceResponse;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.PortfolioRepository;
import id.co.blackheart.service.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioBalanceService {

    private final AccountRepository accountRepository;
    private final PortfolioRepository portfolioRepository;
    private final CacheService cacheService;

    public PortfolioBalanceResponse getBalance(UUID userId) {
        return getBalance(userId, null);
    }

    /**
     * Returns the balance for the user. When {@code accountId} is provided the
     * response is scoped to that single account (after verifying the caller
     * owns it). When {@code accountId} is null we aggregate every account the
     * user owns — this is the "All accounts" view in the sidebar switcher.
     */
    public PortfolioBalanceResponse getBalance(UUID userId, UUID accountId) {
        List<Account> accounts = accountRepository.findByUserId(userId);
        if (accounts.isEmpty()) {
            return emptyResponse(accountId);
        }

        if (accountId != null) {
            boolean ownsAccount = accounts.stream()
                    .anyMatch(a -> accountId.equals(a.getAccountId()));
            if (!ownsAccount) {
                throw new AccessDeniedException("Account does not belong to caller");
            }
            return buildResponse(accountId, portfolioRepository.findAllByAccountId(accountId));
        }

        // Aggregate across every owned account: sum free + locked per asset,
        // then recompute USDT value once on the merged totals so we don't
        // double-multiply by spot price.
        Map<String, AggregatedAsset> byAsset = new LinkedHashMap<>();
        for (Account a : accounts) {
            for (Portfolio p : portfolioRepository.findAllByAccountId(a.getAccountId())) {
                String asset = p.getAsset();
                if (asset == null) continue;
                AggregatedAsset agg = byAsset.computeIfAbsent(asset, k -> new AggregatedAsset());
                if (p.getBalance() != null) agg.free = agg.free.add(p.getBalance());
                if (p.getLocked() != null) agg.locked = agg.locked.add(p.getLocked());
            }
        }

        List<PortfolioAssetResponse> assets = new ArrayList<>(byAsset.size());
        BigDecimal availableUsdt = BigDecimal.ZERO;
        BigDecimal lockedUsdt = BigDecimal.ZERO;
        for (Map.Entry<String, AggregatedAsset> e : byAsset.entrySet()) {
            String asset = e.getKey();
            AggregatedAsset agg = e.getValue();
            BigDecimal usdtValue = computeUsdtValue(asset, agg.free, agg.locked);
            assets.add(PortfolioAssetResponse.builder()
                    .asset(asset)
                    .free(agg.free)
                    .locked(agg.locked)
                    .usdtValue(usdtValue)
                    .build());
            if ("USDT".equalsIgnoreCase(asset)) {
                availableUsdt = availableUsdt.add(agg.free);
                lockedUsdt = lockedUsdt.add(agg.locked);
            }
        }

        BigDecimal totalUsdt = assets.stream()
                .map(PortfolioAssetResponse::getUsdtValue)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return PortfolioBalanceResponse.builder()
                // accountId left null → "all accounts" aggregate marker.
                .accountId(null)
                .totalUsdt(totalUsdt.setScale(8, RoundingMode.HALF_UP))
                .availableUsdt(availableUsdt.setScale(8, RoundingMode.HALF_UP))
                .lockedUsdt(lockedUsdt.setScale(8, RoundingMode.HALF_UP))
                .assets(assets)
                .build();
    }

    private PortfolioBalanceResponse buildResponse(UUID accountId, List<Portfolio> entries) {
        BigDecimal availableUsdt = BigDecimal.ZERO;
        BigDecimal lockedUsdt = BigDecimal.ZERO;

        List<PortfolioAssetResponse> assets = new ArrayList<>(entries.size());
        for (Portfolio p : entries) {
            BigDecimal free = p.getBalance() != null ? p.getBalance() : BigDecimal.ZERO;
            BigDecimal locked = p.getLocked() != null ? p.getLocked() : BigDecimal.ZERO;
            BigDecimal usdtValue = computeUsdtValue(p.getAsset(), free, locked);
            assets.add(PortfolioAssetResponse.builder()
                    .asset(p.getAsset())
                    .free(free)
                    .locked(locked)
                    .usdtValue(usdtValue)
                    .build());
            if ("USDT".equalsIgnoreCase(p.getAsset())) {
                availableUsdt = availableUsdt.add(free);
                lockedUsdt = lockedUsdt.add(locked);
            }
        }

        BigDecimal totalUsdt = assets.stream()
                .map(PortfolioAssetResponse::getUsdtValue)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return PortfolioBalanceResponse.builder()
                .accountId(accountId)
                .totalUsdt(totalUsdt.setScale(8, RoundingMode.HALF_UP))
                .availableUsdt(availableUsdt.setScale(8, RoundingMode.HALF_UP))
                .lockedUsdt(lockedUsdt.setScale(8, RoundingMode.HALF_UP))
                .assets(assets)
                .build();
    }

    private PortfolioBalanceResponse emptyResponse(UUID accountId) {
        return PortfolioBalanceResponse.builder()
                .accountId(accountId)
                .totalUsdt(BigDecimal.ZERO)
                .availableUsdt(BigDecimal.ZERO)
                .lockedUsdt(BigDecimal.ZERO)
                .assets(List.of())
                .build();
    }

    private BigDecimal computeUsdtValue(String asset, BigDecimal free, BigDecimal locked) {
        BigDecimal total = free.add(locked);
        if ("USDT".equalsIgnoreCase(asset)) {
            return total;
        }
        try {
            String symbol = asset + "USDT";
            BigDecimal price = cacheService.getLatestPrice(symbol);
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                return total.multiply(price).setScale(8, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            log.warn("Could not get price for asset {}", asset);
        }
        return BigDecimal.ZERO;
    }

    private static final class AggregatedAsset {
        BigDecimal free = BigDecimal.ZERO;
        BigDecimal locked = BigDecimal.ZERO;
    }
}
