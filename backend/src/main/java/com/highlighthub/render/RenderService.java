package com.highlighthub.render;

import com.highlighthub.common.BusinessException;
import com.highlighthub.common.ErrorCodes;
import com.highlighthub.common.Utils;
import com.highlighthub.media.MediaEntity;
import com.highlighthub.media.MediaAssetEntity;
import com.highlighthub.media.MediaAssetMapper;
import com.highlighthub.media.MediaService;
import com.highlighthub.project.EditingProjectEntity;
import com.highlighthub.project.ProjectRevisionEntity;
import com.highlighthub.project.ProjectService;
import com.highlighthub.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RenderService {
    private static final Logger log = LoggerFactory.getLogger(RenderService.class);
    public static final String RENDERER_VERSION = "renderer-2026.09-v1";
    public static final String PRESET_VERSION = "mp4-h264-aac-v1";

    private final RenderJobMapper renderJobMapper;
    private final MediaAssetMapper assetMapper;
    private final ProjectService projectService;
    private final MediaService mediaService;
    private final TaskService taskService;

    public RenderService(RenderJobMapper renderJobMapper, MediaAssetMapper assetMapper,
                         ProjectService projectService, MediaService mediaService, TaskService taskService) {
        this.renderJobMapper = renderJobMapper;
        this.assetMapper = assetMapper;
        this.projectService = projectService;
        this.mediaService = mediaService;
        this.taskService = taskService;
    }

    /**
     * Rendering binds to an immutable project revision; later edits never affect it.
     */
    @Transactional
    public RenderJobEntity submit(Long ownerId, String projectId, Integer revision, String idempotencyKey) {
        EditingProjectEntity project = projectService.requireOwned(projectId, ownerId);
        if (project.getLatestRevision() <= 0) {
            throw BusinessException.badRequest("project has no saved revision to render");
        }
        int rev = revisionOrDefault(revision, project.getLatestRevision());
        if (rev > project.getLatestRevision()) {
            throw BusinessException.badRequest("revision " + rev + " does not exist yet");
        }
        ProjectRevisionEntity revisionRow = projectService.requireRevision(projectId, rev);
        MediaEntity media = mediaService.requireOwnedMedia(project.getMediaId(), ownerId);
        mediaService.assertStatusReady(media);

        RenderJobEntity job = new RenderJobEntity();
        job.setProjectId(projectId);
        job.setProjectRevision(rev);
        job.setOwnerId(ownerId);
        job.setMediaId(project.getMediaId());
        job.setPresetVersion(PRESET_VERSION);
        job.setRendererVersion(RENDERER_VERSION);
        job.setStatus("QUEUED");
        job.setCreatedAt(Utils.utcNow());
        renderJobMapper.insert(job);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("renderJobId", job.getId());
        payload.put("mediaId", project.getMediaId());
        payload.put("mediaStorageKey", media.getStorageKey());
        payload.put("edl", Utils.fromJson(revisionRow.getEditDecisionJson(), Map.class));
        payload.put("outputKey", "render/" + job.getId() + "/output.mp4");
        payload.put("rendererVersion", RENDERER_VERSION);
        payload.put("presetVersion", PRESET_VERSION);
        var task = taskService.create("RENDER", ownerId, job.getId(), String.valueOf(rev), payload);
        job.setTaskId(task.getId());
        renderJobMapper.updateById(job);
        log.info("render job {} submitted for project {} revision {}", job.getId(), projectId, rev);
        return job;
    }

    private int revisionOrDefault(Integer requested, int latest) {
        return requested == null ? latest : requested;
    }

    public RenderJobEntity requireOwned(String renderId, Long ownerId) {
        RenderJobEntity job = renderJobMapper.findById(renderId);
        if (job == null) throw BusinessException.notFound("render job not found");
        if (!job.getOwnerId().equals(ownerId)) {
            throw BusinessException.notFound("render job not found");
        }
        return job;
    }

    public Map<String, Object> view(RenderJobEntity job) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", job.getId());
        view.put("projectId", job.getProjectId());
        view.put("projectRevision", job.getProjectRevision());
        view.put("status", job.getStatus());
        view.put("presetVersion", job.getPresetVersion());
        view.put("rendererVersion", job.getRendererVersion());
        view.put("taskId", job.getTaskId());
        view.put("createdAt", job.getCreatedAt().toString());
        if (job.getFinishedAt() != null) view.put("finishedAt", job.getFinishedAt().toString());
        if (job.getErrorCode() != null) {
            view.put("errorCode", job.getErrorCode());
            view.put("errorMessage", job.getErrorMessage());
        }
        view.put("outputSize", job.getOutputSize());
        return view;
    }

    // ---- task result application (invoked inside the task-complete transaction) ----

    public void applyRenderResult(String taskId, Map<String, Object> result) {
        RenderJobEntity job = renderJobMapper.findByTaskId(taskId);
        if (job == null) {
            log.warn("render result for unknown task {} ignored", taskId);
            return;
        }
        if (!"QUEUED".equals(job.getStatus()) && !"RUNNING".equals(job.getStatus())) {
            log.warn("render job {} in status {} ignores result", job.getId(), job.getStatus());
            return;
        }
        String storageKey = (String) result.get("storageKey");
        long size = ((Number) result.get("size")).longValue();
        String checksum = (String) result.get("checksum");
        if (storageKey == null || size <= 0 || !storageKey.startsWith("render/") || storageKey.contains("..")) {
            throw new BusinessException(ErrorCodes.INTERNAL, 500, "render output key rejected");
        }
        MediaAssetEntity asset = new MediaAssetEntity();
        asset.setOwnerId(job.getOwnerId());
        asset.setMediaId(null);
        asset.setType("RENDER_OUTPUT");
        asset.setStorageKey(storageKey);
        asset.setSize(size);
        asset.setChecksum(checksum);
        asset.setStatus("ACTIVE");
        asset.setCreatedAt(Utils.utcNow());
        assetMapper.insert(asset);

        job.setStatus("SUCCEEDED");
        job.setOutputAssetId(asset.getId());
        job.setOutputSize(size);
        job.setOutputChecksum(checksum);
        job.setFinishedAt(Utils.utcNow());
        renderJobMapper.updateById(job);
        log.info("render job {} SUCCEEDED ({} bytes)", job.getId(), size);
    }

    public void applyRenderFailure(String taskId, String errorCode, String message) {
        RenderJobEntity job = renderJobMapper.findByTaskId(taskId);
        if (job == null) return;
        if ("SUCCEEDED".equals(job.getStatus())) return;
        job.setStatus("FAILED");
        job.setErrorCode(errorCode);
        job.setErrorMessage(message);
        job.setFinishedAt(Utils.utcNow());
        renderJobMapper.updateById(job);
    }

    public void applyRenderCancelled(String taskId) {
        RenderJobEntity job = renderJobMapper.findByTaskId(taskId);
        if (job == null) return;
        if ("SUCCEEDED".equals(job.getStatus())) return;
        job.setStatus("CANCELLED");
        job.setFinishedAt(Utils.utcNow());
        renderJobMapper.updateById(job);
    }
}
