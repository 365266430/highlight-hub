package com.highlighthub.highlight;

import com.highlighthub.auth.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HighlightController {
    private final HighlightService highlightService;

    public HighlightController(HighlightService highlightService) {
        this.highlightService = highlightService;
    }

    @PostMapping("/analyses/{id}/highlight-runs")
    public Map<String, Object> createRun(@PathVariable String id,
                                         @RequestBody(required = false) HighlightService.RuleParams params) {
        Long userId = SecurityUtils.currentUserId();
        var run = highlightService.createRun(userId, id, params == null
                ? new HighlightService.RuleParams(null, null, null, null, null, null, null, null)
                : params);
        return highlightService.viewRun(run);
    }

    /** existing runs for an analysis so a revisiting user sees prior candidates */
    @GetMapping("/analyses/{id}/highlight-runs")
    public List<Map<String, Object>> runs(@PathVariable String id) {
        Long userId = SecurityUtils.currentUserId();
        return highlightService.listRunsOfAnalysis(id, userId).stream()
                .map(highlightService::viewRun).toList();
    }

    @GetMapping("/highlight-runs/{id}/candidates")
    public List<Map<String, Object>> candidates(@PathVariable String id) {
        highlightService.requireOwnedRun(id, SecurityUtils.currentUserId());
        return highlightService.listCandidatesOfRun(id).stream()
                .map(highlightService::viewCandidate).toList();
    }

    @PatchMapping("/highlight-candidates/{id}")
    public Map<String, Object> decide(@PathVariable String id,
                                      @RequestBody @Valid Map<String, String> body) {
        String status = body.get("status");
        return highlightService.viewCandidate(
                highlightService.decide(SecurityUtils.currentUserId(), id, status));
    }
}
