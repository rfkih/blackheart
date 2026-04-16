package id.co.blackheart.service.portfolio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.client.BinanceClientService;
import id.co.blackheart.client.TokocryptoClientService;
import id.co.blackheart.dto.AssetData;
import id.co.blackheart.dto.request.BinanceAssetRequest;
import id.co.blackheart.dto.response.BinanceAssetDto;
import id.co.blackheart.dto.response.BinanceAssetResponse;
import id.co.blackheart.dto.response.TokocryptoResponse;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.Account;
import id.co.blackheart.repository.PortfolioRepository;
import id.co.blackheart.repository.AccountRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@AllArgsConstructor
public class PortfolioService {

    private final TokocryptoClientService tokocryptoClientService;
    private final BinanceClientService binanceClientService;
    private final AccountRepository accountRepository;
    private final PortfolioRepository portfolioRepository;
    private final ObjectMapper objectMapper;

    private static final int RECV_WINDOW = 5000;

    /**
     * Refreshes the portfolio balance for a single account from Binance asynchronously.
     * Intended as a fire-and-forget call before strategy evaluation so that the
     * <em>next</em> candle sees fresh balance data even if the current one uses the
     * last-persisted value.
     */
    @Async
    public void refreshAccountBalance(Account account) {
        try {
            updateAndGetBinanceAssetBalance(account);
        } catch (Exception e) {
            log.warn("[Portfolio] Background balance refresh failed for account={}", account.getUsername(), e);
        }
    }

    @Async
    public void reloadAsset() {
        log.info("Starting portfolio reload...");
        List<Account> accountList = accountRepository.findByIsActive("1");
        accountList.parallelStream().forEach(account -> {
            try {
                log.info("account {}", account);
                updateAndGetBinanceAssetBalance(account);
            } catch (Exception e) {
                log.error("Error processing User: {} in reloadAsset()", account.getUsername(), e);
            }
        });

        log.info("✅ Portfolio Update Completed!");
    }

    public Map<String, String> checkBinanceConnectivity() {
        List<Account> accountList = accountRepository.findByIsActive("1");
        Map<String, String> result = new LinkedHashMap<>();

        for (Account account : accountList) {
            try {
                BinanceAssetRequest request = new BinanceAssetRequest();
                request.setApiKey(account.getApiKey());
                request.setApiSecret(account.getApiSecret());
                request.setRecvWindow(RECV_WINDOW);
                binanceClientService.getBinanceAssetDetails(request);
                result.put(account.getUsername(), "OK");
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.length() > 120) {
                    msg = msg.substring(0, 120) + "...";
                }
                result.put(account.getUsername(), "ERROR: " + msg);
            }
        }

        return result;
    }

    public Portfolio updateAndGetTokocryptoAssetBalance(String asset, Account user) throws JsonProcessingException {
        log.info("update Data for User: {} and Asset: {}", user.getUsername(), asset);

        TokocryptoResponse response = tokocryptoClientService.getAssetDetails(
                asset, RECV_WINDOW, user.getApiKey(), user.getApiSecret());

        if (response == null || response.getData() == null) {
            log.warn("⚠ No data received for User: {} and Asset: {}", user.getUsername(), asset);
            return null;
        }

        AssetData assetData = objectMapper.treeToValue(response.getData(), AssetData.class);
        return savePortfolio(user, asset, assetData.getFree(), assetData.getLocked());
    }

    public void updateAndGetBinanceAssetBalance(Account account) {
        BinanceAssetRequest binanceAssetRequest = new BinanceAssetRequest();
        binanceAssetRequest.setApiKey(account.getApiKey());
        binanceAssetRequest.setApiSecret(account.getApiSecret());
        binanceAssetRequest.setRecvWindow(RECV_WINDOW);

        BinanceAssetResponse binanceAssetResponse = binanceClientService.getBinanceAssetDetails(binanceAssetRequest);

        if (binanceAssetResponse == null || binanceAssetResponse.getAssets() == null) {
            log.warn("[Portfolio] No asset data returned from Binance for account={}", account.getUsername());
            return;
        }

        saveAllBinanceAssets(account, binanceAssetResponse.getAssets());
    }

    public Portfolio updateAndGetAssetBalance(String asset, Account user) throws JsonProcessingException {
        log.info("Updating data for User: {} and Asset: {}", user.getUsername(), asset);

        BinanceAssetRequest request = new BinanceAssetRequest();
        request.setApiKey(user.getApiKey());
        request.setApiSecret(user.getApiSecret());
        request.setRecvWindow(RECV_WINDOW);

        BinanceAssetResponse response = binanceClientService.getBinanceAssetDetails(request);
        saveAllBinanceAssets(user, response.getAssets());

        return response.getAssets().stream()
                .filter(a -> a.getAsset().equalsIgnoreCase(asset))
                .findFirst()
                .map(dto -> savePortfolio(user, dto.getAsset(), dto.getFree(), dto.getLocked()))
                .orElse(null);


    }

    private Portfolio savePortfolio(Account user, String asset, String free, String locked) {
        Portfolio portfolio = portfolioRepository.findByAccountIdAndAsset(user.getAccountId(), asset)
                .orElse(new Portfolio());

        portfolio.setAccountId(user.getAccountId());
        portfolio.setAsset(asset);
        portfolio.setIsActive("1");
        portfolio.setBalance(new BigDecimal(free));
        portfolio.setLocked(new BigDecimal(locked));
        portfolio.setUpdatedAt(LocalDateTime.now());

        portfolioRepository.save(portfolio);
        return portfolio;
    }

    protected void saveAllBinanceAssets(Account user, List<BinanceAssetDto> assetList) {
        assetList.forEach(dto -> savePortfolio(user, dto.getAsset(), dto.getFree(), dto.getLocked()));
    }
}
