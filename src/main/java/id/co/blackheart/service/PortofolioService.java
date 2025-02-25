package id.co.blackheart.service;


import id.co.blackheart.client.PortofolioClientService;
import id.co.blackheart.dto.AssetDto;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@Slf4j
@AllArgsConstructor
public class PortofolioService {

    private final PortofolioClientService portofolioClientService;



    public void reloadEChannelBiaya() throws IOException {
        log.info("Updating portofolio ");
        AssetDto assetDto = portofolioClientService.getSingleAsset();
        log.info("assetDto: {}", assetDto.toString());
        log.info("portofolio Updated");
    }

}
