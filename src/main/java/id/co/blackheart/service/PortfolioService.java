package id.co.blackheart.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.client.TokocryptoClientService;
import id.co.blackheart.dto.AssetData;
import id.co.blackheart.dto.TokocryptoResponse;
import id.co.blackheart.model.Portfolio;
import id.co.blackheart.model.Users;
import id.co.blackheart.repository.PortfolioRepository;
import id.co.blackheart.repository.UsersRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@AllArgsConstructor
public class PortfolioService {

    private final TokocryptoClientService tokocryptoClientService;
    private final UsersRepository usersRepository;
    private final PortfolioRepository portfolioRepository;




    public void reloadAsset() {
        log.info("Starting portfolio reload...");
        String assets = "IDR,BTC,USDT";
        String recvWindow = "5000";
        String[] assetArray = assets.split(",");
        List<Users> userList = usersRepository.findByIsActive("1");

        userList.parallelStream().forEach(user -> {
            for (String asset : assetArray) {
                try {
                    updateAndGetAssetBalance(asset, user, recvWindow);
                } catch (Exception e) {
                    log.error("Error processing User: {} and Asset: {}", user.getUsername(), asset, e);
                }
            }
        });

        log.info("✅ Portfolio Update Completed!");
    }


    public Portfolio updateAndGetAssetBalance(String asset, Users user, String recvWindow) throws JsonProcessingException {
        log.info("update Data for User: {} and Asset: {}", user.getUsername(), asset);
        TokocryptoResponse response = tokocryptoClientService.getAssetDetails(asset, Integer.parseInt(recvWindow), user.getApiKey(), user.getApiSecret());
        if (response == null || response.getData() == null) {
            log.warn("⚠No data received for User: {} and Asset: {}", user.getUsername(), asset);
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
        portfolio.setBalance(freeBalance);
        portfolio.setLocked(lockedBalance);
        portfolio.setUpdatedAt(LocalDateTime.now());

        portfolioRepository.save(portfolio);
        return portfolio;
    }

}
