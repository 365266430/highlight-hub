package com.highlighthub.task;

import com.highlighthub.common.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Worker-only endpoints. Service-level auth (X-Worker-Token) is enforced by
 * RequestAndWorkerFilter; attemptToken identifies a single execution attempt.
 */
@RestController
@RequestMapping("/internal/tasks")
public class TaskInternalController {
    private final TaskService taskService;

    public TaskInternalController(TaskService taskService) {
        this.taskService = taskService;
    }

    public record ClaimRequest(String workerId, List<String> types, Integer maxCount, Integer leaseSeconds) {}

    public record HeartbeatRequest(String attemptToken, Integer leaseSeconds) {}

    public record ProgressRequest(String attemptToken, Integer progress, String phase, Integer leaseSeconds) {}

    public record CompleteRequest(String attemptToken, String outputRef, Object result) {}

    public record FailRequest(String attemptToken, String errorCode, String errorMessage, Boolean retryable) {}

    @PostMapping("/claim")
    public List<Map<String, Object>> claim(@RequestBody ClaimRequest req) {
        if (req.workerId() == null || req.workerId().isBlank() || req.types() == null || req.types().isEmpty()) {
            throw BusinessException.badRequest("workerId and types are required");
        }
        int maxCount = req.maxCount() == null ? 1 : Math.min(Math.max(1, req.maxCount()), 10);
        int leaseSeconds = req.leaseSeconds() == null ? 120 : Math.min(Math.max(30, req.leaseSeconds()), 1800);
        List<TaskEntity> claimed = taskService.claim(req.workerId(), req.types(), maxCount, leaseSeconds);
        return claimed.stream().map(t -> Map.<String, Object>of(
                "taskId", t.getId(),
                "type", t.getType(),
                "attempt", t.getAttempt(),
                "attemptToken", t.getAttemptToken(),
                "inputRef", t.getInputRef(),
                "inputVersion", t.getInputVersion() == null ? "" : t.getInputVersion(),
                "payload", t.getPayloadJson() == null ? Map.of() : com.highlighthub.common.Utils.fromJson(
                        t.getPayloadJson(), Map.class),
                "leaseUntil", t.getLeaseUntil().toString()
        )).toList();
    }

    @PostMapping("/{id}/heartbeat")
    public Map<String, Object> heartbeat(@PathVariable String id, @RequestBody HeartbeatRequest req) {
        if (req.attemptToken() == null) throw BusinessException.badRequest("attemptToken required");
        int leaseSeconds = req.leaseSeconds() == null ? 120 : Math.min(Math.max(30, req.leaseSeconds()), 1800);
        taskService.heartbeat(id, req.attemptToken(), leaseSeconds);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/progress")
    public Map<String, Object> progress(@PathVariable String id, @RequestBody ProgressRequest req) {
        if (req.attemptToken() == null) throw BusinessException.badRequest("attemptToken required");
        int leaseSeconds = req.leaseSeconds() == null ? 120 : Math.min(Math.max(30, req.leaseSeconds()), 1800);
        int progress = req.progress() == null ? 0 : req.progress();
        taskService.progress(id, req.attemptToken(), progress, req.phase(), leaseSeconds);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/complete")
    public Map<String, Object> complete(@PathVariable String id, @RequestBody CompleteRequest req) {
        if (req.attemptToken() == null) throw BusinessException.badRequest("attemptToken required");
        String status = taskService.complete(id, req.attemptToken(), req.outputRef(), req.result());
        return Map.of("ok", true, "status", status);
    }

    @PostMapping("/{id}/fail")
    public Map<String, Object> fail(@PathVariable String id, @RequestBody FailRequest req) {
        if (req.attemptToken() == null) throw BusinessException.badRequest("attemptToken required");
        taskService.fail(id, req.attemptToken(), req.errorCode() == null ? "WORKER_ERROR" : req.errorCode(),
                req.errorMessage(), req.retryable() == null || req.retryable());
        return Map.of("ok", true);
    }

    @GetMapping("/{id}/cancellation")
    public Map<String, Object> cancellation(@PathVariable String id, @RequestParam String attemptToken) {
        boolean requested = taskService.isCancelRequested(id, attemptToken);
        return Map.of("cancelRequested", requested);
    }
}
