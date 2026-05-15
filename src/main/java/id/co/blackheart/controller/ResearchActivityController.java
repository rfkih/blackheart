package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResearchActivityResponse;
import id.co.blackheart.dto.response.ResearchSessionSummaryResponse;
import id.co.blackheart.service.research.ResearchActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/research/activity")
@RequiredArgsConstructor
@Profile("research")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class ResearchActivityController {

    private final ResearchActivityService service;

    @GetMapping
    public ResponseEntity<Page<ResearchActivityResponse>> listActivities(
            @RequestParam(required = false) UUID sessionId,
            @PageableDefault(size = 50, sort = "createdTime", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(service.listActivities(sessionId, pageable));
    }

    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> listSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<ResearchSessionSummaryResponse> sessions = service.listSessions(page, size);
        long total = service.countSessions();
        return ResponseEntity.ok(Map.of(
                "content", sessions,
                "page", page,
                "size", size,
                "total", total
        ));
    }
}
