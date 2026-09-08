package com.highlighthub.analysis;

import com.highlighthub.auth.SecurityUtils;
import com.highlighthub.common.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnalysisController {
    private final AnalysisService analysisService;
    private final EventController eventController;
    private final IdempotencyService idempotencyService;

    public AnalysisController(AnalysisService analysisService, EventController eventController,
                              IdempotencyService idempotencyService) {
        this.analysisService = analysisService;
        this.eventController = eventController;
        this.idempotencyService = idempotencyService;
    }

    public record CreateAnalysisRequest(@NotBlank String adapterVersionId, Map<String, Object> params) {}

    @PostMapping("/media/{id}/analyses")
    public Map<String, Object> create(@PathVariable String id,
                                      @RequestBody @Valid CreateAnalysisRequest req,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long userId = SecurityUtils.currentUserId();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("mediaId", id);
        fingerprint.put("adapterVersionId", req.adapterVersionId());
        return (Map<String, Object>) idempotencyService.execute(userId, "ANALYSIS_CREATE", idemKey,
                fingerprint, () -> analysisService.view(
                        analysisService.createAutoRun(userId, id, req.adapterVersionId(),
                                req.params(), idemKey)));
    }

    @GetMapping("/analyses/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return analysisService.view(analysisService.requireOwned(id, SecurityUtils.currentUserId()));
    }

    @GetMapping("/analyses/{id}/events")
    public List<Map<String, Object>> events(@PathVariable String id) {
        return analysisService.eventsOf(id, SecurityUtils.currentUserId()).stream()
                .map(EventController::view).toList();
    }
}
