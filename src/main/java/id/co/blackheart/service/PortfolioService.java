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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@AllArgsConstructor
public class PortfolioService {

    private final TokocryptoClientService tokocryptoClientService;
    private final BinanceClientService binanceClientService;
    private final UsersRepository usersRepository;
    private final PortfolioRepository portfolioRepository;

    public void reloadAsset() {
        log.info("Starting portfolio reload...");
        String assets = "IDR,BTC,USDT,TKO";
        String recvWindow = "5000";
        String[] assetArray = assets.split(",");
        List<Users> userList = usersRepository.findByIsActive("1");

        userList.parallelStream().forEach(user -> {
            try {
                if (user.getExchange().equals("TKO")) {
                    for (String asset : assetArray) {
                        updateAndGetTokocryptoAssetBalance(asset, user, recvWindow);
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

    public Portfolio updateAndGetTokocryptoAssetBalance(String asset, Users user, String recvWindow) throws JsonProcessingException {
        log.info("update Data for User: {} and Asset: {}", user.getUsername(), asset);

        TokocryptoResponse response = tokocryptoClientService.getAssetDetails(
                asset, Integer.parseInt(recvWindow), user.getApiKey(), user.getApiSecret());

        if (response == null || response.getData() == null) {
            log.warn("⚠ No data received for User: {} and Asset: {}", user.getUsername(), asset);
            return null;
        }

        ObjectMapper objectMapper = new ObjectMapper();
        AssetData assetData = objectMapper.treeToValue(response.getData(), AssetData.class);

        BigDecimal freeBalance = new BigDecimal(assetData.getFree());
        BigDecimal lockedBalance = new BigDecimal(assetData.getLocked());

        Portfolio portfolio = portfolioRepository.findByUserIdAndAsset(user.getId(), asset)
                .orElse(new Portfolio());

        portfolio.setUserId(user.getId());
        portfolio.setAsset(asset);
        portfolio.setIsActive("1");
        portfolio.setBalance(freeBalance);
        portfolio.setLocked(lockedBalance);
        portfolio.setUpdatedAt(LocalDateTime.now());

        portfolioRepository.save(portfolio);
        return portfolio;
    }

    public List<Portfolio> updateAndGetBinanceAssetBalance(Users user) {
        BinanceAssetRequest binanceAssetRequest = new BinanceAssetRequest();
        binanceAssetRequest.setApiKey(user.getApiKey());
        binanceAssetRequest.setApiSecret(user.getApiSecret());
        binanceAssetRequest.setRecvWindow(5000);

        BinanceAssetResponse binanceAssetResponse = binanceClientService.getBinanceAssetDetails(binanceAssetRequest);
        log.info("binanceAssetResponse: {}", binanceAssetResponse.getAssets().size());

        List<Portfolio> listPortfolio = new ArrayList<>();
        for (BinanceAssetDto binanceAssetDto : binanceAssetResponse.getAssets()) {
            Portfolio portfolio = portfolioRepository.findByUserIdAndAsset(user.getId(), binanceAssetDto.getAsset())
                    .orElse(new Portfolio());

            portfolio.setUserId(user.getId());
            portfolio.setAsset(binanceAssetDto.getAsset());
            portfolio.setIsActive("1");
            portfolio.setBalance(new BigDecimal(binanceAssetDto.getFree()));
            portfolio.setLocked(new BigDecimal(binanceAssetDto.getLocked()));
            portfolio.setUpdatedAt(LocalDateTime.now());

            portfolioRepository.save(portfolio);
            listPortfolio.add(portfolio);
        }
        return listPortfolio;
    }

    public Portfolio updateAndGetAssetBalance(String asset, Users user) throws JsonProcessingException {
        log.info("Updating data for User: {} and Asset: {}", user.getUsername(), asset);

        Portfolio portfolio = portfolioRepository.findByUserIdAndAsset(user.getId(), asset)
                .orElse(new Portfolio());

        if ("TKO".equals(user.getExchange())) {
            // Tokocrypto logic
            TokocryptoResponse response = tokocryptoClientService.getAssetDetails(
                    asset, 5000, user.getApiKey(), user.getApiSecret());

            if (response == null || response.getData() == null) {
                log.warn("⚠ No data received from Tokocrypto for User: {} and Asset: {}", user.getUsername(), asset);
                return null;
            }

            AssetData assetData = new ObjectMapper().treeToValue(response.getData(), AssetData.class);
            portfolio.setBalance(new BigDecimal(assetData.getFree()));
            portfolio.setLocked(new BigDecimal(assetData.getLocked()));
        } else {
            // Binance logic (separate background bulk save)
            BinanceAssetRequest request = new BinanceAssetRequest();
            request.setApiKey(user.getApiKey());
            request.setApiSecret(user.getApiSecret());
            request.setRecvWindow(5000);

            BinanceAssetResponse response = binanceClientService.getBinanceAssetDetails(request);

            // Save ALL Binance assets in background
            saveAllBinanceAssets(user, response.getAssets());

            // Return only the requested asset
            BinanceAssetDto matchingAsset = response.getAssets().stream()
                    .filter(a -> a.getAsset().equalsIgnoreCase(asset))
                    .findFirst()
                    .orElse(null);

            if (matchingAsset == null) {
                log.warn("⚠ Asset {} not found in Binance response for User: {}", asset, user.getUsername());
                return null;
            }

            portfolio.setBalance(new BigDecimal(matchingAsset.getFree()));
            portfolio.setLocked(new BigDecimal(matchingAsset.getLocked()));
        }

        portfolio.setUserId(user.getId());
        portfolio.setAsset(asset);
        portfolio.setIsActive("1");
        portfolio.setUpdatedAt(LocalDateTime.now());
        portfolioRepository.save(portfolio);

        return portfolio;
    }

    private void saveAllBinanceAssets(Users user, List<BinanceAssetDto> assetList) {
        for (BinanceAssetDto dto : assetList) {
            Portfolio portfolio = portfolioRepository.findByUserIdAndAsset(user.getId(), dto.getAsset())
                    .orElse(new Portfolio());

            portfolio.setUserId(user.getId());
            portfolio.setAsset(dto.getAsset());
            portfolio.setIsActive("1");
            portfolio.setBalance(new BigDecimal(dto.getFree()));
            portfolio.setLocked(new BigDecimal(dto.getLocked()));
            portfolio.setUpdatedAt(LocalDateTime.now());

            portfolioRepository.save(portfolio);
        }
    }

}
