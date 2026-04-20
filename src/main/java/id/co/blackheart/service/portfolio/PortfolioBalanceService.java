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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioBalanceService {

    private final AccountRepository accountRepository;
    private final PortfolioRepository portfolioRepository;
    private final CacheService cacheService;

    public PortfolioBalanceResponse getBalance(UUID userId) {
        List<Account> accounts = accountRepository.findByUserId(userId);
        if (accounts.isEmpty()) {
            return PortfolioBalanceResponse.builder()
                    .totalUsdt(BigDecimal.ZERO)
                    .availableUsdt(BigDecimal.ZERO)
                    .lockedUsdt(BigDecimal.ZERO)
                    .assets(List.of())
                    .build();
        }

        Account account = accounts.get(0);
        UUID accountId = account.getAccountId();

        List<Portfolio> entries = portfolioRepository.findAllByAccountId(accountId);

        BigDecimal availableUsdt = BigDecimal.ZERO;
        BigDecimal lockedUsdt = BigDecimal.ZERO;

        List<PortfolioAssetResponse> assets = entries.stream().map(p -> {
            BigDecimal free = p.getBalance() != null ? p.getBalance() : BigDecimal.ZERO;
            BigDecimal locked = p.getLocked() != null ? p.getLocked() : BigDecimal.ZERO;
            BigDecimal usdtValue = computeUsdtValue(p.getAsset(), free, locked);
            return PortfolioAssetResponse.builder()
                    .asset(p.getAsset())
                    .free(free)
                    .locked(locked)
                    .usdtValue(usdtValue)
                    .build();
        }).collect(Collectors.toList());

        for (Portfolio p : entries) {
            if ("USDT".equalsIgnoreCase(p.getAsset())) {
                if (p.getBalance() != null) availableUsdt = availableUsdt.add(p.getBalance());
                if (p.getLocked() != null) lockedUsdt = lockedUsdt.add(p.getLocked());
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
}
