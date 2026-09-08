package com.highlighthub.analysis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.highlighthub.adapter.AdapterVersionEntity;
import com.highlighthub.adapter.GenericAdapterSeeder;
import com.highlighthub.common.BusinessException;
import com.highlighthub.common.ErrorCodes;
import com.highlighthub.common.Utils;
import com.highlighthub.media.MediaAssetEntity;
import com.highlighthub.media.MediaAssetMapper;
import com.highlighthub.media.MediaEntity;
import com.highlighthub.media.MediaService;
import com.highlighthub.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    public static final String ALGORITHM_VERSION = "ocr-analyzer-2026.09-v1";

    private final AnalysisRunMapper runMapper;
    private final VideoEventMapper eventMapper;
    private final MediaAssetMapper assetMapper;
    private final MediaService mediaService;
    private final TaskService taskService;
    private final GenericAdapterSeeder adapterSeeder;

    public AnalysisService(AnalysisRunMapper runMapper, VideoEventMapper eventMapper,
                           MediaAssetMapper assetMapper, MediaService mediaService,
                           TaskService taskService, GenericAdapterSeeder adapterSeeder) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.assetMapper = assetMapper;
        this.mediaService = mediaService;
        this.taskService = taskService;
        this.adapterSeeder = adapterSeeder;
    }

    /** user picks an adapter version (or manual mode); the run binds that exact version */
    @Transactional
    public AnalysisRunEntity createAutoRun(Long ownerId, String mediaId, String adapterVersionId,
                                           Map<String, Object> paramOverrides, String idempotencyKey) {
        MediaEntity media = mediaService.requireOwnedMedia(mediaId, ownerId);
        mediaService.assertStatusReady(media);
        AdapterVersionEntity version = adapterSeeder.requireUsableVersion(adapterVersionId);
        Map<String, Object> params = paramOverrides == null ? new LinkedHashMap<>() : paramOverrides;

        AnalysisRunEntity run = new AnalysisRunEntity();
        run.setMediaId(mediaId);
        run.setOwnerId(ownerId);
        run.setAdapterVersionId(version.getId());
        run.setMode("AUTO");
        run.setParamsJson(Utils.toJson(params));
        run.setParamsHash(Utils.sha256Hex(run.getParamsJson()));
        run.setAlgorithmVersion(ALGORITHM_VERSION);
        run.setStatus("QUEUED");
        run.setCreatedAt(Utils.utcNow());
        runMapper.insert(run);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("analysisRunId", run.getId());
        payload.put("mediaId", mediaId);
        payload.put("mediaStorageKey", media.getStorageKey());
        payload.put("adapterVersionId", version.getId());
        payload.put("config", mergedConfig(version, params));
        var task = taskService.create("ANALYZE", ownerId, run.getId(), version.getId(), payload);
        run.setTaskId(task.getId());
        runMapper.updateById(run);
        log.info("analysis run {} queued for media {} with adapter version {}", run.getId(), mediaId, version.getId());
        return run;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergedConfig(AdapterVersionEntity version, Map<String, Object> overrides) {
        Map<String, Object> base = Utils.fromJson(version.getConfigJson(), Map.class);
        if (base == null) base = new LinkedHashMap<>();
        Object rois = overrides.remove("rois");
        if (rois != null) base.put("rois", rois); // per-run ROI calibration overrides the template
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            base.put(e.getKey(), e.getValue());
        }
        return base;
    }

    public AnalysisRunEntity requireOwned(String runId, Long ownerId) {
        AnalysisRunEntity run = runMapper.findById(runId);
        if (run == null || !run.getOwnerId().equals(ownerId)) {
            throw BusinessException.notFound("analysis run not found");
        }
        return run;
    }

    public Map<String, Object> view(AnalysisRunEntity run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.getId());
        view.put("mediaId", run.getMediaId());
        view.put("mode", run.getMode());
        view.put("adapterVersionId", run.getAdapterVersionId());
        view.put("algorithmVersion", run.getAlgorithmVersion());
        view.put("status", run.getStatus());
        view.put("taskId", run.getTaskId());
        view.put("createdAt", run.getCreatedAt().toString());
        if (run.getFinishedAt() != null) view.put("finishedAt", run.getFinishedAt().toString());
        return view;
    }

    // ---- ANALYZE task result application ----

    @EventListener
    @Transactional
    public void onTaskSucceeded(TaskService.TaskSucceededEvent event) {
        if (!"ANALYZE".equals(event.type())) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> result = event.result() instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        if (result == null || !Boolean.TRUE.equals(result.get("ok"))) {
            throw new IllegalStateException("analyze result missing or invalid");
        }
        AnalysisRunEntity run = runMapper.findById(event.inputRef());
        if (run == null || "SUCCEEDED".equals(run.getStatus())) return;
        if (Boolean.TRUE.equals(result.get("unsupported"))) {
            run.setStatus("FAILED");
            run.setFinishedAt(Utils.utcNow());
            runMapper.updateById(run);
            log.warn("analysis run {} input unsupported by adapter; user must recalibrate or use manual mode",
                    run.getId());
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
        int count = 0;
        if (events != null) {
            for (Map<String, Object> ev : events) {
                persistEvent(run, ev);
                count++;
            }
        }
        run.setStatus("SUCCEEDED");
        run.setFinishedAt(Utils.utcNow());
        runMapper.updateById(run);
        log.info("analysis run {} SUCCEEDED with {} events", run.getId(), count);
    }

    @EventListener
    @Transactional
    public void onTaskFailed(TaskService.TaskFailedEvent event) {
        if (!"ANALYZE".equals(event.type())) return;
        AnalysisRunEntity run = runMapper.findById(event.inputRef());
        if (run == null || "SUCCEEDED".equals(run.getStatus())) return;
        run.setStatus("FAILED");
        run.setFinishedAt(Utils.utcNow());
        runMapper.updateById(run);
    }

    @EventListener
    @Transactional
    public void onTaskCancelled(TaskService.TaskCancelledEvent event) {
        if (!"ANALYZE".equals(event.type())) return;
        AnalysisRunEntity run = runMapper.findById(event.inputRef());
        if (run == null) return;
        if (!"SUCCEEDED".equals(run.getStatus())) {
            run.setStatus("CANCELLED");
            run.setFinishedAt(Utils.utcNow());
            runMapper.updateById(run);
        }
    }

    private void persistEvent(AnalysisRunEntity run, Map<String, Object> ev) {
        VideoEventEntity e = new VideoEventEntity();
        e.setAnalysisRunId(run.getId());
        e.setMediaId(run.getMediaId());
        e.setOwnerId(run.getOwnerId());
        e.setType(String.valueOf(ev.getOrDefault("type", "MANUAL_MARKER")));
        e.setStartMs(((Number) ev.get("startMs")).longValue());
        e.setEndMs(ev.get("endMs") == null ? null : ((Number) ev.get("endMs")).longValue());
        if (ev.get("confidence") instanceof Number c) {
            e.setConfidence(new java.math.BigDecimal(String.valueOf(c.doubleValue())));
        }
        e.setSource("AUTO");
        e.setStatus("ACTIVE");
        e.setAdapterVersionId(run.getAdapterVersionId());
        Map<String, Object> attrs = ev.get("attributes") instanceof Map<?, ?> am
                ? (Map<String, Object>) am : new LinkedHashMap<>();
        // recognized text is stored as attributes; actor attribution is NOT inferred
        e.setAttributes(Utils.toJson(attrs));

        String evidenceKey = (String) ev.get("evidenceStorageKey");
        if (evidenceKey != null) {
            MediaAssetEntity asset = new MediaAssetEntity();
            asset.setOwnerId(run.getOwnerId());
            asset.setMediaId(run.getMediaId());
            asset.setType("EVIDENCE");
            asset.setStorageKey(evidenceKey);
            asset.setSize(ev.get("evidenceSize") instanceof Number n ? n.longValue() : 0L);
            asset.setStatus("ACTIVE");
            asset.setCreatedAt(Utils.utcNow());
            assetMapper.insert(asset);
            e.setEvidenceAssetId(asset.getId());
        }
        e.setCreatedAt(Utils.utcNow());
        e.setUpdatedAt(Utils.utcNow());
        eventMapper.insert(e);
    }

    public List<VideoEventEntity> eventsOf(String runId, Long ownerId) {
        AnalysisRunEntity run = requireOwned(runId, ownerId);
        return eventMapper.selectList(new QueryWrapper<VideoEventEntity>()
                .eq("analysis_run_id", run.getId())
                .eq("status", "ACTIVE")
                .orderByAsc("start_ms"));
    }
}
