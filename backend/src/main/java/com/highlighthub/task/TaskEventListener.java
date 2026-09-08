package com.highlighthub.task;

import com.highlighthub.media.MediaService;
import com.highlighthub.render.RenderService;
import com.highlighthub.storage.LocalStorageService;
import com.highlighthub.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Routes terminal task events to the owning business module. Success listeners
 * run inside the task-completion transaction; a failure here rolls the whole
 * completion back so the worker can retry.
 */
@Component
public class TaskEventListener {
    private static final Logger log = LoggerFactory.getLogger(TaskEventListener.class);

    private final MediaService mediaService;
    private final RenderService renderService;
    private final LocalStorageService storage;

    public TaskEventListener(MediaService mediaService, RenderService renderService,
                             LocalStorageService storage) {
        this.mediaService = mediaService;
        this.renderService = renderService;
        this.storage = storage;
    }

    @EventListener
    public void onTaskSucceeded(TaskService.TaskSucceededEvent event) {
        Map<String, Object> result = event.result() instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        switch (event.type()) {
            case "PROBE" -> {
                if (result != null && Boolean.TRUE.equals(result.get("ok"))) {
                    mediaService.applyProbeResult(event.inputRef(), result);
                } else {
                    throw new IllegalStateException("probe result missing or invalid");
                }
            }
            case "PREVIEW" -> {
                if (result != null && Boolean.TRUE.equals(result.get("ok"))) {
                    mediaService.applyPreviewResult(event.inputRef(), result);
                } else {
                    throw new IllegalStateException("preview result missing or invalid");
                }
            }
            case "THUMBNAIL" -> {
                if (result != null && Boolean.TRUE.equals(result.get("ok"))) {
                    mediaService.applyThumbnailResult(event.inputRef(), result);
                } else {
                    throw new IllegalStateException("thumbnail result missing or invalid");
                }
            }
            case "RENDER" -> renderService.applyRenderResult(event.taskId(), result);
            case "ANALYZE" -> {
                // analysis results are applied by the analysis module (phase 2)
                log.info("ANALYZE task {} succeeded; event routing added in phase 2", event.taskId());
            }
            case "GENERATE_CANDIDATES" -> log.info("GENERATE_CANDIDATES task {} succeeded", event.taskId());
            case "CLEANUP" -> log.info("CLEANUP task {} completed for {}", event.taskId(), event.inputRef());
            default -> log.warn("no listener for task type {}", event.type());
        }
    }

    @EventListener
    public void onTaskFailed(TaskService.TaskFailedEvent event) {
        switch (event.type()) {
            case "PROBE", "PREVIEW", "THUMBNAIL" -> mediaService.markMediaFailed(event.inputRef(),
                    event.errorCode(), event.errorMessage());
            case "RENDER" -> renderService.applyRenderFailure(event.taskId(), event.errorCode(), event.errorMessage());
            default -> log.warn("task {} type {} failed: {}: {}", event.taskId(), event.type(),
                    event.errorCode(), event.errorMessage());
        }
    }

    @EventListener
    public void onTaskCancelled(TaskService.TaskCancelledEvent event) {
        switch (event.type()) {
            case "RENDER" -> renderService.applyRenderCancelled(event.taskId());
            default -> log.info("task {} type {} cancelled", event.taskId(), event.type());
        }
    }
}
