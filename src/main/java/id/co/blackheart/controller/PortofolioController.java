package id.co.blackheart.controller;


import id.co.blackheart.dto.ResponseDto;
import id.co.blackheart.service.PortofolioService;
import id.co.blackheart.util.ResponseCode;
import id.co.blackheart.util.ResponseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;

@RestController
@RequestMapping(value = "v1/portofolio")
@Slf4j
@Tag(name = "PortofolioController", description = "Controller for Portofolio")
public class PortofolioController {

    private static final String RELOAD_SUCCESS = "Update Portofolio Success";

    @Autowired
    private PortofolioService portofolioService;

    @GetMapping("/reload")
    public ResponseEntity<ResponseDto> portofolioUpdate() throws IOException {
        portofolioService.reloadEChannelBiaya();
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(RELOAD_SUCCESS)
                .build());
    }

}
