package id.co.blackheart.controller;


import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.portfolio.PortfolioService;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;



@RestController
// Accept both the canonical `/api/v1/portofolio/**` path and the shorter
// `/v1/portofolio/**` alias so existing Postman collections / curl scripts
// keep working. Prefer the `/api`-prefixed form for new callers — it mirrors
// the rest of the controllers in this project.
@RequestMapping(value = {"/api/v1/portofolio", "/v1/portofolio"})
@Slf4j
@RequiredArgsConstructor
@Tag(name = "PortofolioController", description = "Controller for Portofolio")
@PreAuthorize("hasRole('ADMIN')")
public class PortofolioController {

    private static final String RELOAD_SUCCESS = "Update Portofolio Success";

    private final PortfolioService portofolioService;

    @GetMapping("/reload")
    public ResponseEntity<ResponseDto> portofolioUpdate() {
        portofolioService.reloadAsset();
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(RELOAD_SUCCESS)
                .build());
    }

}
