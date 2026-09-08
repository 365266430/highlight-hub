package com.highlighthub.project;

import com.highlighthub.common.BusinessException;
import com.highlighthub.common.ErrorCodes;
import com.highlighthub.common.Utils;
import com.highlighthub.media.MediaEntity;
import com.highlighthub.media.MediaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProjectService {
    private final EditingProjectMapper projectMapper;
    private final ProjectRevisionMapper revisionMapper;
    private final MediaService mediaService;

    @Value("${highlight-hub.project.max-segments}")
    private int maxSegments;

    @Value("${highlight-hub.project.max-total-output-seconds}")
    private long maxTotalOutputSeconds;

    @Value("${highlight-hub.project.max-output-height}")
    private int maxOutputHeight;

    @Value("${highlight-hub.project.max-caption-chars}")
    private int maxCaptionChars;

    public ProjectService(EditingProjectMapper projectMapper, ProjectRevisionMapper revisionMapper,
                          MediaService mediaService) {
        this.projectMapper = projectMapper;
        this.revisionMapper = revisionMapper;
        this.mediaService = mediaService;
    }

    @Transactional
    public EditingProjectEntity create(Long ownerId, String mediaId, String name) {
        MediaEntity media = mediaService.requireOwnedMedia(mediaId, ownerId);
        mediaService.assertStatusReady(media);
        EditingProjectEntity project = new EditingProjectEntity();
        project.setOwnerId(ownerId);
        project.setMediaId(mediaId);
        project.setName(validName(name));
        project.setLatestRevision(0);
        project.setStatus("ACTIVE");
        project.setCreatedAt(Utils.utcNow());
        project.setUpdatedAt(Utils.utcNow());
        projectMapper.insert(project);
        return project;
    }

    private String validName(String name) {
        if (name == null || name.isBlank()) return "未命名工程";
        String trimmed = name.trim();
        if (trimmed.length() > 120) return trimmed.substring(0, 120);
        return trimmed;
    }

    public EditingProjectEntity requireOwned(String projectId, Long ownerId) {
        EditingProjectEntity p = projectMapper.findOwned(projectId, ownerId);
        if (p == null) throw BusinessException.notFound("project not found");
        return p;
    }

    public Map<String, Object> view(EditingProjectEntity p) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", p.getId());
        view.put("mediaId", p.getMediaId());
        view.put("name", p.getName());
        view.put("latestRevision", p.getLatestRevision());
        view.put("status", p.getStatus());
        view.put("createdAt", p.getCreatedAt().toString());
        view.put("updatedAt", p.getUpdatedAt().toString());
        ProjectRevisionEntity latest = p.getLatestRevision() > 0
                ? revisionMapper.findRevision(p.getId(), p.getLatestRevision()) : null;
        if (latest != null) view.put("edl", Utils.fromJson(latest.getEditDecisionJson(), Map.class));
        return view;
    }

    /**
     * Optimistic-concurrency save: client submits expectedRevision; on success a new
     * immutable revision row is appended. Concurrent savers get 409.
     */
    @Transactional
    public int save(Long ownerId, String projectId, int expectedRevision, String name, EdlValidator.Edl edl) {
        EditingProjectEntity p = requireOwned(projectId, ownerId);
        if (expectedRevision != p.getLatestRevision()) {
            throw new BusinessException(ErrorCodes.CONFLICT, 409,
                    "expectedRevision " + expectedRevision + " does not match current " + p.getLatestRevision());
        }
        MediaEntity media = mediaService.requireOwnedMedia(p.getMediaId(), ownerId);
        if (edl != null && !edl.sourceMediaId().equals(p.getMediaId())) {
            throw BusinessException.badRequest("sourceMediaId does not match the project's media");
        }
        EdlValidator.Edl toStore = edl != null ? edl : emptyEdl(p.getMediaId());
        EdlValidator.validate(toStore, media.getDurationMs() == null ? 0 : media.getDurationMs(),
                maxSegments, maxTotalOutputSeconds * 1000, maxOutputHeight, maxCaptionChars);
        int bumped = projectMapper.bumpRevision(projectId, expectedRevision, validName(name));
        if (bumped == 0) {
            throw new BusinessException(ErrorCodes.CONFLICT, 409, "project was modified concurrently");
        }
        int newRevision = expectedRevision + 1;
        ProjectRevisionEntity rev = new ProjectRevisionEntity();
        rev.setProjectId(projectId);
        rev.setRevision(newRevision);
        rev.setSchemaVersion(1);
        rev.setEditDecisionJson(EdlValidator.normalize(toStore));
        rev.setCreatedAt(Utils.utcNow());
        revisionMapper.insert(rev);
        return newRevision;
    }

    private EdlValidator.Edl emptyEdl(String mediaId) {
        return new EdlValidator.Edl(1, mediaId, List.of(), new EdlValidator.Output("SOURCE", 1920, 1080, 30), List.of());
    }

    public ProjectRevisionEntity requireRevision(String projectId, int revision) {
        ProjectRevisionEntity rev = revisionMapper.findRevision(projectId, revision);
        if (rev == null) throw BusinessException.notFound("revision not found");
        return rev;
    }

    public List<ProjectRevisionEntity> listRevisions(String projectId) {
        return revisionMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ProjectRevisionEntity>()
                .eq("project_id", projectId).orderByDesc("revision"));
    }

    public Map.Entry<List<Map<String, Object>>, Long> listPage(Long ownerId, int page, int size) {
        long offset = (long) (Math.max(page, 1) - 1) * Math.min(size, 100);
        List<Map<String, Object>> items = projectMapper.listOwned(ownerId, offset, Math.min(size, 100))
                .stream().map(this::view).toList();
        long total = projectMapper.countOwned(ownerId);
        return Map.entry(items, total);
    }
}
