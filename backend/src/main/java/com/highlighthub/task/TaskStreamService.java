package com.highlighthub.task;

import com.highlighthub.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE push of task progress/terminal events to the OWNING user only.
 * The database remains the state authority; reconnecting clients fall back
 * to REST (GET /api/tasks) - push is an optimization, never a second truth.
 */
@Service
public class TaskStreamService {
    private static final Logger log = LoggerFactory.getLogger(TaskStreamService.class);

    private final Map<Long, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter register(Long userId) {
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout; client may disconnect freely
        List<SseEmitter> list = emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("connected")
                    .data(Utils.toJson(Map.of("ok", true))));
        } catch (IOException e) {
            list.remove(emitter);
        }
        return emitter;
    }

    private void send(Long userId, String name, Map<String, Object> payload) {
        List<SseEmitter> list = emittersByUser.get(userId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(name).data(Utils.toJson(payload)));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
            }
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        emittersByUser.keySet().forEach(userId -> send(userId, "ping", Map.of("t", System.currentTimeMillis())));
    }

    // ---- Spring events coming from the task state machine ----

    @EventListener
    public void onProgress(TaskService.TaskProgressEvent e) {
        send(e.ownerId(), "task", Map.of(
                "taskId", e.taskId(), "type", e.type(), "status", "RUNNING",
                "progress", e.progress(), "phase", e.phase() == null ? "" : e.phase()));
    }

    @EventListener
    public void onSucceeded(TaskService.TaskSucceededEvent e) {
        send(e.ownerId(), "task", Map.of(
                "taskId", e.taskId(), "type", e.type(), "status", "SUCCEEDED",
                "inputRef", e.inputRef(), "progress", 100));
    }

    @EventListener
    public void onFailed(TaskService.TaskFailedEvent e) {
        send(e.ownerId(), "task", Map.of(
                "taskId", e.taskId(), "type", e.type(), "status", "FAILED",
                "inputRef", e.inputRef(), "errorCode", e.errorCode()));
    }

    @EventListener
    public void onCancelled(TaskService.TaskCancelledEvent e) {
        send(e.ownerId(), "task", Map.of(
                "taskId", e.taskId(), "type", e.type(), "status", "CANCELLED",
                "inputRef", e.inputRef()));
    }
}
