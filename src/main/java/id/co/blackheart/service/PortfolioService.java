package id.co.blackheart.service;

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
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.PortfolioRepository;
import id.co.blackheart.repository.UsersRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@AllArgsConstructor
public class PortfolioService {

    private final TokocryptoClientService tokocryptoClientService;
    private final BinanceClientService binanceClientService;
    private final UsersRepository usersRepository;
    private final PortfolioRepository portfolioRepository;
    private final ObjectMapper objectMapper;

    private static final int RECV_WINDOW = 5000;

    public void reloadAsset() {
        log.info("Starting portfolio reload...");
        String[] assetArray = {"IDR", "BTC", "USDT", "TKO"};
        List<Users> userList = usersRepository.findByIsActive("1");

        userList.parallelStream().forEach(user -> {
            try {
                if ("TKO".equals(user.getExchange())) {
                    for (String asset : assetArray) {
                        updateAndGetTokocryptoAssetBalance(asset, user);
                    }
                } else {
                    updateAndGetBinanceAssetBalance(user);
                }
            } catch (Exception e) {
                log.error("Error processing User: {} in reloadAsset()", user.getUsername(), e);
            }
        });

        log.info("✅ Portfolio Update Completed!");
    }

    public Portfolio updateAndGetTokocryptoAssetBalance(String asset, Users user) throws JsonProcessingException {
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

    public List<Portfolio> updateAndGetBinanceAssetBalance(Users user) {
        BinanceAssetRequest binanceAssetRequest = new BinanceAssetRequest();
        binanceAssetRequest.setApiKey(user.getApiKey());
        binanceAssetRequest.setApiSecret(user.getApiSecret());
        binanceAssetRequest.setRecvWindow(RECV_WINDOW);

        BinanceAssetResponse binanceAssetResponse = binanceClientService.getBinanceAssetDetails(binanceAssetRequest);

        // Save everything async
        saveAllBinanceAssets(user, binanceAssetResponse.getAssets());

        return binanceAssetResponse.getAssets().stream()
                .map(dto -> savePortfolio(user, dto.getAsset(), dto.getFree(), dto.getLocked()))
                .toList();
    }

    public Portfolio updateAndGetAssetBalance(String asset, Users user) throws JsonProcessingException {
        log.info("Updating data for User: {} and Asset: {}", user.getUsername(), asset);

        if ("TKO".equals(user.getExchange())) {
            return updateAndGetTokocryptoAssetBalance(asset, user);
        } else if ("BNC".equals(user.getExchange())){
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
        return new Portfolio();
    }

    private Portfolio savePortfolio(Users user, String asset, String free, String locked) {
        Portfolio portfolio = portfolioRepository.findByUserIdAndAsset(user.getId(), asset)
                .orElse(new Portfolio());

        portfolio.setUserId(user.getId());
        portfolio.setAsset(asset);
        portfolio.setIsActive("1");
        portfolio.setBalance(new BigDecimal(free));
        portfolio.setLocked(new BigDecimal(locked));
        portfolio.setUpdatedAt(LocalDateTime.now());

        portfolioRepository.save(portfolio);
        return portfolio;
    }

    @Async
    protected void saveAllBinanceAssets(Users user, List<BinanceAssetDto> assetList) {
        assetList.forEach(dto -> savePortfolio(user, dto.getAsset(), dto.getFree(), dto.getLocked()));
    }
}
