package com.highlighthub.project;

import com.highlighthub.auth.SecurityUtils;
import com.highlighthub.common.BusinessException;
import com.highlighthub.common.IdempotencyService;
import com.highlighthub.render.RenderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService projectService;
    private final RenderService renderService;
    private final IdempotencyService idempotencyService;

    public ProjectController(ProjectService projectService, RenderService renderService,
                             IdempotencyService idempotencyService) {
        this.projectService = projectService;
        this.renderService = renderService;
        this.idempotencyService = idempotencyService;
    }

    public record CreateProjectRequest(@NotBlank String mediaId, String name) {}

    public record SegmentDto(String id, Long sourceInMs, Long sourceOutMs, String caption, Double sourceVolume) {}
    public record OutputDto(String aspectMode, Integer width, Integer height, Integer fps) {}
    public record SaveProjectRequest(Integer expectedRevision, String name, Integer schemaVersion,
                                     String sourceMediaId, List<SegmentDto> segments, OutputDto output,
                                     List<EdlValidator.Mask> masks) {}

    @PostMapping
    public Map<String, Object> create(@RequestBody @Valid CreateProjectRequest req) {
        var project = projectService.create(SecurityUtils.currentUserId(), req.mediaId(), req.name());
        return projectService.view(project);
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        var mapper = projectService.listPage(SecurityUtils.currentUserId(), page, size);
        return Map.of("items", mapper.getKey(), "page", page, "total", mapper.getValue());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return projectService.view(projectService.requireOwned(id, SecurityUtils.currentUserId()));
    }

    @PutMapping("/{id}")
    public Map<String, Object> save(@PathVariable String id, @RequestBody SaveProjectRequest req) {
        if (req.expectedRevision() == null) {
            throw BusinessException.badRequest("expectedRevision is required for saving");
        }
        EdlValidator.Edl edl = toEdl(req, id);
        int newRevision = projectService.save(SecurityUtils.currentUserId(), id, req.expectedRevision(),
                req.name(), edl);
        return Map.of("id", id, "revision", newRevision);
    }

    private EdlValidator.Edl toEdl(SaveProjectRequest req, String projectId) {
        var project = projectService.requireOwned(projectId, SecurityUtils.currentUserId());
        String sourceMediaId = req.sourceMediaId() == null ? project.getMediaId() : req.sourceMediaId();
        List<EdlValidator.Segment> segments = req.segments() == null ? List.of() : req.segments().stream()
                .map(s -> new EdlValidator.Segment(s.id(), s.sourceInMs() == null ? 0 : s.sourceInMs(),
                        s.sourceOutMs() == null ? 0 : s.sourceOutMs(),
                        s.caption() == null ? "" : s.caption(),
                        s.sourceVolume() == null ? 1.0 : s.sourceVolume()))
                .toList();
        EdlValidator.Output output = req.output() == null
                ? new EdlValidator.Output("SOURCE", 1920, 1080, 30)
                : new EdlValidator.Output(req.output().aspectMode() == null ? "SOURCE" : req.output().aspectMode(),
                        req.output().width() == null ? 1920 : req.output().width(),
                        req.output().height() == null ? 1080 : req.output().height(),
                        req.output().fps() == null ? 30 : req.output().fps());
        return new EdlValidator.Edl(1, sourceMediaId, segments, output, req.masks());
    }

    @GetMapping("/{id}/revisions")
    public List<Map<String, Object>> revisions(@PathVariable String id) {
        projectService.requireOwned(id, SecurityUtils.currentUserId());
        return projectService.listRevisions(id).stream()
                .map(r -> Map.<String, Object>of(
                        "revision", r.getRevision(),
                        "schemaVersion", r.getSchemaVersion(),
                        "createdAt", r.getCreatedAt().toString(),
                        "edl", com.highlighthub.common.Utils.fromJson(r.getEditDecisionJson(), Map.class)))
                .toList();
    }

    @GetMapping("/{id}/revisions/{revision}")
    public Map<String, Object> revision(@PathVariable String id, @PathVariable int revision) {
        projectService.requireOwned(id, SecurityUtils.currentUserId());
        var r = projectService.requireRevision(id, revision);
        return Map.of(
                "revision", r.getRevision(),
                "schemaVersion", r.getSchemaVersion(),
                "createdAt", r.getCreatedAt().toString(),
                "edl", com.highlighthub.common.Utils.fromJson(r.getEditDecisionJson(), Map.class));
    }

    /** submit a render bound to (optionally) a specific immutable revision */
    @PostMapping("/{id}/renders")
    public Map<String, Object> submitRender(@PathVariable String id,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long userId = SecurityUtils.currentUserId();
        Integer revision = body == null ? null : (Integer) body.get("projectRevision");
        Map<String, Object> requestFingerprint = new java.util.LinkedHashMap<>();
        requestFingerprint.put("projectId", id);
        requestFingerprint.put("revision", revision);
        var result = idempotencyService.execute(userId, "RENDER_SUBMIT", idemKey, requestFingerprint, () -> {
            var job = renderService.submit(userId, id, revision, idemKey);
            return renderService.view(job);
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) result;
        return view;
    }
}
