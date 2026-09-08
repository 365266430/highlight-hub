package com.highlighthub.task;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.highlighthub.auth.SecurityUtils;
import com.highlighthub.common.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService taskService;
    private final com.highlighthub.task.TaskStreamService taskStreamService;

    public TaskController(TaskService taskService, com.highlighthub.task.TaskStreamService taskStreamService) {
        this.taskService = taskService;
        this.taskStreamService = taskStreamService;
    }

    public static Map<String, Object> view(TaskEntity t) {
        return Map.ofEntries(
                Map.entry("id", t.getId()),
                Map.entry("type", t.getType()),
                Map.entry("status", t.getStatus()),
                Map.entry("attempt", t.getAttempt()),
                Map.entry("maxAttempts", t.getMaxAttempts()),
                Map.entry("progress", t.getProgress()),
                Map.entry("phase", t.getPhase() == null ? "" : t.getPhase()),
                Map.entry("inputRef", t.getInputRef()),
                Map.entry("errorCode", t.getErrorCode() == null ? "" : t.getErrorCode()),
                Map.entry("errorMessage", t.getErrorMessage() == null ? "" : t.getErrorMessage()),
                Map.entry("createdAt", t.getCreatedAt() == null ? "" : t.getCreatedAt().toString()),
                Map.entry("startedAt", t.getStartedAt() == null ? "" : t.getStartedAt().toString()),
                Map.entry("finishedAt", t.getFinishedAt() == null ? "" : t.getFinishedAt().toString())
        );
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String type) {
        Long userId = SecurityUtils.currentUserId();
        IPage<TaskEntity> p = taskService.pageForOwner(userId, page, size, status, type);
        List<Map<String, Object>> records = p.getRecords().stream().map(TaskController::view).toList();
        return Map.of("items", records, "page", p.getCurrent(), "total", p.getTotal());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return view(taskService.requireOwnedTask(id, SecurityUtils.currentUserId()));
    }

    /** SSE stream of the user's own task events; REST list remains the reconnect source of truth */
    @org.springframework.web.bind.annotation.GetMapping("/stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream() {
        return taskStreamService.register(SecurityUtils.currentUserId());
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        String status = taskService.requestCancel(id, SecurityUtils.currentUserId(),
                SecurityUtils.currentUser().getRole().equals("ADMIN"));
        return Map.of("id", id, "status", status);
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id) {
        TaskEntity t = taskService.retry(id, SecurityUtils.currentUserId(),
                SecurityUtils.currentUser().getRole().equals("ADMIN"));
        return view(t);
    }
}
