package id.co.blackheart.service;


import id.co.blackheart.client.TokocryptoClientService;
import id.co.blackheart.dto.AssetData;
import id.co.blackheart.dto.TokocryptoResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@AllArgsConstructor
public class PortofolioService {

    private final TokocryptoClientService tokocryptoClientService;



    public void reloadAsset() throws Exception {
        log.info("🔄 Updating portfolio...");
        String recvWindow = "5000";
        String asset = "IDR";

        TokocryptoResponse response = tokocryptoClientService.getAssetDetails(asset, Integer.parseInt(recvWindow));


        AssetData assetData = response.getData();


        log.info("✅ AssetDto: {}", assetData);
        log.info("✅ Portfolio Updated!");
    }


}
