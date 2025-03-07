package id.co.blackheart.service;


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



    public void reloadAsset() throws Exception {
        log.info("Updating portfolio...");

        String recvWindow = "5000";
        String assets = "IDR,BTC"; // Comma-separated asset list

        List<Users> userList = usersRepository.findByIsActive("1");

        for (Users user : userList) {
            log.info("Processing user: {}", user.getId());

            List<Portfolio> existingPortfolios = portfolioRepository.findByUserId(user.getId());

            Map<String, Portfolio> portfolioMap = existingPortfolios.stream()
                    .collect(Collectors.toMap(Portfolio::getAsset, portfolio -> portfolio));

            for (String asset : assets.split(",")) {
                try {
                    TokocryptoResponse response = tokocryptoClientService.getAssetDetails(asset, Integer.parseInt(recvWindow), user.getApiKey(), user.getApiSecret());

                    if (response == null || response.getData() == null) {
                        log.warn("⚠No data received for User: {} and Asset: {}", user.getUsername(), asset);
                        continue;
                    }

                    AssetData assetData = response.getData();
                    BigDecimal freeBalance = new BigDecimal(assetData.getFree());
                    BigDecimal lockedBalance = new BigDecimal(assetData.getLocked());

                    if (portfolioMap.containsKey(asset)) {
                        // Update existing portfolio
                        Portfolio portfolio = portfolioMap.get(asset);
                        portfolio.setBalance(freeBalance);
                        portfolio.setLocked(lockedBalance);
                        portfolio.setUpdatedAt(LocalDateTime.now());

                        portfolioRepository.save(portfolio);
                        log.info("Updated Portfolio for User {} and Asset {}", user.getUsername(), asset);
                    } else {
                        // Insert new portfolio record
                        Portfolio newPortfolio = new Portfolio();
                        newPortfolio.setUserId(user.getId());
                        newPortfolio.setAsset(asset);
                        newPortfolio.setBalance(freeBalance);
                        newPortfolio.setLocked(lockedBalance);
                        newPortfolio.setIsActive("1");
                        newPortfolio.setCreatedAt(LocalDateTime.now());

                        portfolioRepository.save(newPortfolio);
                        log.info("Inserted New Portfolio for User {} and Asset {}", user.getUsername(), asset);
                    }
                } catch (Exception e) {
                    log.info("Error processing User: {} and Asset: {} - {}", user.getUsername(), asset, e.getMessage(), e);
                }
            }
        }

        log.info("✅ Portfolio Update Completed!");
    }

}
