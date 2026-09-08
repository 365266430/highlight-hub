package com.highlighthub.analysis;

import com.highlighthub.auth.SecurityUtils;
import com.highlighthub.common.Utils;
import com.highlighthub.common.BusinessException;
import com.highlighthub.common.Utils;
import com.highlighthub.media.MediaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manual events (phase 1). Auto events arrive in phase 2 through the same
 * table; user revisions keep the original analysis record intact via
 * event_revisions history.
 */
@RestController
@RequestMapping("/api")
public class EventController {
    private final VideoEventMapper eventMapper;
    private final MediaService mediaService;

    private static final Set<String> MANUAL_TYPES = Set.of(
            "MANUAL_MARKER", "ELIMINATION_NOTICE", "SCORE_CHANGE", "ROUND_START", "ROUND_END");

    public EventController(VideoEventMapper eventMapper, MediaService mediaService) {
        this.eventMapper = eventMapper;
        this.mediaService = mediaService;
    }

    public record CreateEventRequest(@NotBlank String type, @NotNull Long startMs, Long endMs,
                                     String note) {}

    public record PatchEventRequest(Long startMs, Long endMs, String type, String note) {}

    public static Map<String, Object> view(VideoEventEntity e) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", e.getId());
        view.put("mediaId", e.getMediaId());
        view.put("analysisRunId", e.getAnalysisRunId());
        view.put("type", e.getType());
        view.put("startMs", e.getStartMs());
        view.put("endMs", e.getEndMs());
        view.put("source", e.getSource());
        view.put("status", e.getStatus());
        view.put("confidence", e.getConfidence() == null ? null : e.getConfidence().doubleValue());
        view.put("attributes", e.getAttributes() == null ? Map.of() : Utils.fromJson(e.getAttributes(), Map.class));
        view.put("createdAt", e.getCreatedAt().toString());
        return view;
    }

    @PostMapping("/media/{id}/events")
    public Map<String, Object> create(@PathVariable String id, @RequestBody @Valid CreateEventRequest req) {
        Long userId = SecurityUtils.currentUserId();
        mediaService.requireOwnedMedia(id, userId);
        if (!MANUAL_TYPES.contains(req.type())) {
            throw BusinessException.badRequest("unsupported event type " + req.type());
        }
        if (req.startMs() < 0) throw BusinessException.badRequest("startMs must be >= 0");
        if (req.endMs() != null && req.endMs() < req.startMs()) {
            throw BusinessException.badRequest("endMs must be >= startMs");
        }
        VideoEventEntity e = new VideoEventEntity();
        e.setMediaId(id);
        e.setOwnerId(userId);
        e.setType(req.type());
        e.setStartMs(req.startMs());
        e.setEndMs(req.endMs());
        e.setSource("MANUAL");
        e.setStatus("ACTIVE");
        if (req.note() != null && !req.note().isBlank()) {
            e.setAttributes(Utils.toJson(Map.of("note", req.note())));
        }
        e.setCreatedAt(Utils.utcNow());
        e.setUpdatedAt(Utils.utcNow());
        eventMapper.insert(e);
        eventMapper.insertRevision(e.getId(), 1, userId,
                Utils.toJson(Map.of("action", "CREATE", "type", e.getType(),
                        "startMs", e.getStartMs(), "source", "MANUAL")), req.note());
        return view(e);
    }

    @GetMapping("/media/{id}/events")
    public List<Map<String, Object>> list(@PathVariable String id) {
        Long userId = SecurityUtils.currentUserId();
        mediaService.requireOwnedMedia(id, userId);
        return eventMapper.listActiveByMedia(id, userId).stream().map(EventController::view).toList();
    }

    @PatchMapping("/events/{id}")
    public Map<String, Object> patch(@PathVariable String id, @RequestBody PatchEventRequest req) {
        Long userId = SecurityUtils.currentUserId();
        VideoEventEntity e = requireOwned(id, userId);
        Map<String, Object> change = new LinkedHashMap<>();
        if (req.startMs() != null) {
            if (req.startMs() < 0) throw BusinessException.badRequest("startMs must be >= 0");
            change.put("startMs", req.startMs());
            e.setStartMs(req.startMs());
        }
        if (req.endMs() != null) {
            change.put("endMs", req.endMs());
            e.setEndMs(req.endMs());
        }
        if (req.type() != null) {
            if (!MANUAL_TYPES.contains(req.type())) {
                throw BusinessException.badRequest("unsupported event type " + req.type());
            }
            change.put("type", req.type());
            e.setType(req.type());
        }
        if (e.getEndMs() != null && e.getStartMs() != null && e.getEndMs() < e.getStartMs()) {
            throw BusinessException.badRequest("endMs must be >= startMs");
        }
        e.setUpdatedAt(Utils.utcNow());
        eventMapper.updateById(e);
        eventMapper.insertRevision(e.getId(), nextRevision(e.getId()), userId,
                Utils.toJson(change), req.note());
        return view(e);
    }

    @PostMapping("/events/{id}/reject")
    public Map<String, Object> reject(@PathVariable String id) {
        Long userId = SecurityUtils.currentUserId();
        VideoEventEntity e = requireOwned(id, userId);
        e.setStatus("REJECTED");
        e.setUpdatedAt(Utils.utcNow());
        eventMapper.updateById(e);
        eventMapper.insertRevision(e.getId(), nextRevision(e.getId()), userId,
                Utils.toJson(Map.of("action", "REJECT")), null);
        return view(e);
    }

    private VideoEventEntity requireOwned(String id, Long userId) {
        VideoEventEntity e = eventMapper.findById(id);
        if (e == null || !e.getOwnerId().equals(userId)) {
            throw BusinessException.notFound("event not found");
        }
        return e;
    }

    private int nextRevision(String eventId) {
        return eventMapper.currentRevision(eventId) + 1;
    }
}
