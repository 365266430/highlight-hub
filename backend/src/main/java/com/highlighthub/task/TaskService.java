package com.highlighthub.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.highlighthub.common.BusinessException;
import com.highlighthub.common.ErrorCodes;
import com.highlighthub.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    private final TaskMapper taskMapper;
    private final TaskAttemptMapper attemptMapper;
    private final ApplicationEventPublisher events;
    private final com.highlighthub.outbox.Outbox.Recorder outbox;

    @Value("${highlight-hub.task.default-lease-seconds}")
    private int defaultLeaseSeconds;

    @Value("${highlight-hub.task.max-attempts}")
    private int defaultMaxAttempts;

    public TaskService(TaskMapper taskMapper, TaskAttemptMapper attemptMapper,
                       ApplicationEventPublisher events, com.highlighthub.outbox.Outbox.Recorder outbox) {
        this.taskMapper = taskMapper;
        this.attemptMapper = attemptMapper;
        this.events = events;
        this.outbox = outbox;
    }

    public TaskEntity create(String type, Long ownerId, String inputRef, String inputVersion, Object payload) {
        return create(type, ownerId, inputRef, inputVersion, payload, defaultMaxAttempts);
    }

    public TaskEntity create(String type, Long ownerId, String inputRef, String inputVersion,
                             Object payload, int maxAttempts) {
        TaskEntity task = new TaskEntity();
        task.setId(Utils.newId());
        task.setOwnerId(ownerId);
        task.setType(type);
        task.setInputRef(inputRef);
        task.setInputVersion(inputVersion);
        task.setPayloadJson(payload == null ? null : Utils.toJson(payload));
        task.setStatus(QUEUED);
        task.setAttempt(0);
        task.setMaxAttempts(maxAttempts);
        task.setProgress(0);
        task.setNextRunAt(Utils.utcNow());
        task.setCreatedAt(Utils.utcNow());
        task.setUpdatedAt(Utils.utcNow());
        taskMapper.insert(task);
        return task;
    }

    /** Atomic claim across competing workers; rows locked FOR UPDATE SKIP LOCKED inside the tx. */
    @Transactional
    public List<TaskEntity> claim(String workerId, List<String> types, int maxCount, int leaseSeconds) {
        List<TaskEntity> candidates = taskMapper.selectQueuedForUpdate(types, maxCount);
        List<TaskEntity> claimed = new java.util.ArrayList<>();
        for (TaskEntity task : candidates) {
            String token = Utils.newToken();
            LocalDateTime leaseUntil = Utils.utcPlusSeconds(leaseSeconds);
            int updated = taskMapper.markRunning(task.getId(), token, workerId, leaseUntil);
            if (updated == 1) {
                task.setStatus(RUNNING);
                task.setAttempt(task.getAttempt() + 1);
                task.setAttemptToken(token);
                task.setWorkerId(workerId);
                task.setLeaseUntil(leaseUntil);
                claimed.add(task);
                attemptMapper.insertRunning(task.getId(), task.getAttempt(), token, workerId);
            }
        }
        return claimed;
    }

    private void requireCurrentAttempt(String taskId, String attemptToken) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw BusinessException.notFound("task not found");
        if (attemptToken == null || !attemptToken.equals(task.getAttemptToken())) {
            throw BusinessException.conflict(ErrorCodes.TASK_CONFLICT, "attempt token is stale");
        }
        if (!RUNNING.equals(task.getStatus())) {
            throw BusinessException.conflict(ErrorCodes.TASK_CONFLICT,
                    "task is not running (status=" + task.getStatus() + ")");
        }
    }

    public void heartbeat(String taskId, String attemptToken, int leaseSeconds) {
        int updated = taskMapper.renewLease(taskId, attemptToken, Utils.utcPlusSeconds(leaseSeconds));
        if (updated == 0) {
            requireCurrentAttempt(taskId, attemptToken); // produces a precise 409 message
            throw BusinessException.conflict(ErrorCodes.TASK_CONFLICT, "heartbeat rejected");
        }
        attemptMapper.touchHeartbeat(taskId, attemptToken);
    }

    public void progress(String taskId, String attemptToken, int progress, String phase, int leaseSeconds) {
        int p = Math.max(0, Math.min(100, progress));
        int updated = taskMapper.updateProgress(taskId, attemptToken, p, phase, Utils.utcPlusSeconds(leaseSeconds));
        if (updated == 0) {
            requireCurrentAttempt(taskId, attemptToken);
            throw BusinessException.conflict(ErrorCodes.TASK_CONFLICT, "progress rejected");
        }
        TaskEntity task = taskMapper.selectById(taskId);
        if (task != null && task.getOwnerId() != null) {
            events.publishEvent(new TaskProgressEvent(taskId, task.getOwnerId(), task.getType(), p, phase));
        }
    }

    public boolean isCancelRequested(String taskId, String attemptToken) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw BusinessException.notFound("task not found");
        if (attemptToken == null || !attemptToken.equals(task.getAttemptToken())) {
            throw BusinessException.conflict(ErrorCodes.TASK_CONFLICT, "attempt token is stale");
        }
        // the worker polls precisely when cancel was requested, so CANCEL_REQUESTED is a valid state here
        if (!RUNNING.equals(task.getStatus()) && !CANCEL_REQUESTED.equals(task.getStatus())) {
            throw BusinessException.conflict(ErrorCodes.TASK_CONFLICT,
                    "task is not running (status=" + task.getStatus() + ")");
        }
        return task.getCancelRequestedAt() != null || CANCEL_REQUESTED.equals(task.getStatus());
    }

    /**
     * Success is committed atomically with its business side effects
     * (listeners run inside the same transaction).
     * Returns the final task status: SUCCEEDED, or CANCELLED when a user cancel
     * arrived during execution (a late success never resurrects the task).
     */
    @Transactional
    public String complete(String taskId, String attemptToken, String outputRef, Object result) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw BusinessException.notFound("task not found");
        if (CANCEL_REQUESTED.equals(task.getStatus())) {
            // late success after user cancel: confirm the cancellation instead
            if (attemptToken.equals(task.getAttemptToken())) {
                attemptMapper.finish(taskId, task.getAttempt(), "CANCELLED", null, null);
            }
            if (taskMapper.markCancelled(taskId) == 1) {
                events.publishEvent(new TaskCancelledEvent(taskId, task.getType(), task.getOwnerId(), task.getInputRef()));
            }
            log.info("task {} success arrived after cancel; recorded CANCELLED", taskId);
            return CANCELLED;
        }
        requireCurrentAttempt(taskId, attemptToken);
        int updated = taskMapper.markSucceeded(taskId, attemptToken, outputRef);
        if (updated == 0) {
            throw BusinessException.conflict(ErrorCodes.TASK_CONFLICT, "task can no longer be completed");
        }
        attemptMapper.finish(taskId, task.getAttempt(), "SUCCEEDED", null, null);
        TaskEntity fresh = taskMapper.selectById(taskId);
        events.publishEvent(new TaskSucceededEvent(taskId, task.getType(), task.getOwnerId(),
                task.getInputRef(), task.getInputVersion(), outputRef, result));
        outbox.record("task.succeeded", taskId, task.getAttempt(),
                java.util.Map.of("type", task.getType(), "inputRef", task.getInputRef(),
                        "ownerId", task.getOwnerId() == null ? 0 : task.getOwnerId()));
        log.info("task {} type {} SUCCEEDED by token {}", fresh.getId(), task.getType(), abbreviate(attemptToken));
        return SUCCEEDED;
    }

    /** retryable=false marks unrecoverable input errors: no requeue. */
    @Transactional
    public void fail(String taskId, String attemptToken, String errorCode, String errorMessage, boolean retryable) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw BusinessException.notFound("task not found");
        if (CANCEL_REQUESTED.equals(task.getStatus())) {
            attemptMapper.finish(taskId, task.getAttempt(), "CANCELLED", errorCode, errorMessage);
            if (taskMapper.markCancelled(taskId) == 1) {
                events.publishEvent(new TaskCancelledEvent(taskId, task.getType(), task.getOwnerId(), task.getInputRef()));
            }
            return;
        }
        requireCurrentAttempt(taskId, attemptToken);
        boolean willRetry = retryable && task.getAttempt() < task.getMaxAttempts();
        LocalDateTime now = Utils.utcNow();
        LocalDateTime nextRun = willRetry ? Utils.utcPlusSeconds(backoffSeconds(task.getAttempt())) : now;
        int updated = taskMapper.finishAttempt(taskId, attemptToken,
                willRetry ? QUEUED : FAILED, errorCode, errorMessage,
                willRetry ? nextRun : null, willRetry ? null : now);
        if (updated == 0) {
            throw BusinessException.conflict(ErrorCodes.TASK_CONFLICT, "task attempt can no longer be failed");
        }
        attemptMapper.finish(taskId, task.getAttempt(), willRetry ? "FAILED" : "FAILED", errorCode, errorMessage);
        if (!willRetry) {
            events.publishEvent(new TaskFailedEvent(taskId, task.getType(), task.getOwnerId(),
                    task.getInputRef(), errorCode, errorMessage));
            outbox.record("task.failed", taskId, task.getAttempt(),
                    java.util.Map.of("type", task.getType(), "errorCode", errorCode,
                            "ownerId", task.getOwnerId() == null ? 0 : task.getOwnerId()));
        }
        log.info("task {} attempt {} {} (code={})", taskId, task.getAttempt(),
                willRetry ? "requeued" : "FAILED", errorCode);
    }

    private long backoffSeconds(int attempt) {
        return Math.min(15L * attempt, 300L);
    }

    /** user-visible cancel; returns the post-request status */
    @Transactional
    public String requestCancel(String taskId, Long requesterId, boolean isAdmin) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw BusinessException.notFound("task not found");
        if (!isAdmin && !requesterId.equals(task.getOwnerId())) {
            throw BusinessException.forbidden("not your task");
        }
        if (Set.of(SUCCEEDED, FAILED, CANCELLED).contains(task.getStatus())) {
            return task.getStatus(); // idempotent
        }
        if (QUEUED.equals(task.getStatus())) {
            taskMapper.cancelQueued(taskId);
            attemptMapper.finish(task.getId(), task.getAttempt(), "CANCELLED", "CANCELLED_BY_USER", null);
            events.publishEvent(new TaskCancelledEvent(taskId, task.getType(), task.getOwnerId(), task.getInputRef()));
            return CANCELLED;
        }
        taskMapper.requestCancelRunning(taskId);
        return CANCEL_REQUESTED;
    }

    /** retry by owner: only FAILED tasks restart from the first failed stage */
    @Transactional
    public TaskEntity retry(String taskId, Long requesterId, boolean isAdmin) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw BusinessException.notFound("task not found");
        if (!isAdmin && !requesterId.equals(task.getOwnerId())) {
            throw BusinessException.forbidden("not your task");
        }
        if (!FAILED.equals(task.getStatus())) {
            throw BusinessException.conflict(ErrorCodes.TASK_CONFLICT, "only failed tasks can be retried");
        }
        int updated = taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<TaskEntity>()
                .eq("id", taskId)
                .eq("status", FAILED)
                .set("status", QUEUED)
                .set("attempt", 0)
                .set("error_code", null)
                .set("error_message", null)
                .set("progress", 0)
                .set("next_run_at", Utils.utcNow())
                .set("updated_at", Utils.utcNow()));
        if (updated == 0) {
            throw BusinessException.conflict(ErrorCodes.TASK_CONFLICT, "task state changed, retry again");
        }
        return taskMapper.selectById(taskId);
    }

    /** requeue expired leases and confirm abandoned cancels */
    @Transactional
    public void sweepExpiredLeases() {
        for (TaskEntity task : taskMapper.findExpiredLeases()) {
            boolean willRetry = task.getAttempt() < task.getMaxAttempts();
            LocalDateTime now = Utils.utcNow();
            int updated = taskMapper.finishAttempt(task.getId(), task.getAttemptToken(),
                    willRetry ? QUEUED : FAILED, "LEASE_LOST",
                    "worker lease expired mid-run",
                    willRetry ? Utils.utcPlusSeconds(backoffSeconds(task.getAttempt())) : null,
                    willRetry ? null : now);
            if (updated == 1) {
                attemptMapper.finish(task.getId(), task.getAttempt(),
                        willRetry ? "TIMEOUT" : "TIMEOUT", "LEASE_LOST", "worker lease expired");
                if (!willRetry) {
                    events.publishEvent(new TaskFailedEvent(task.getId(), task.getType(), task.getOwnerId(),
                            task.getInputRef(), "LEASE_LOST", "worker lease expired, retries exhausted"));
                }
                log.warn("task {} attempt {} lease lost -> {}", task.getId(), task.getAttempt(),
                        willRetry ? "QUEUED" : "FAILED");
            }
        }
        for (TaskEntity task : taskMapper.findStaleCancelRequested()) {
            // worker died after cancel was requested; confirm cancellation
            int updated = taskMapper.markCancelled(task.getId());
            if (updated == 1) {
                attemptMapper.finish(task.getId(), task.getAttempt(), "CANCELLED", "CANCELLED", null);
                events.publishEvent(new TaskCancelledEvent(task.getId(), task.getType(), task.getOwnerId(), task.getInputRef()));
                log.info("task {} stale cancel-requested -> CANCELLED", task.getId());
            }
        }
    }

    public TaskEntity requireTask(String taskId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw BusinessException.notFound("task not found");
        return task;
    }

    public TaskEntity requireOwnedTask(String taskId, Long ownerId) {
        TaskEntity task = requireTask(taskId);
        if (!ownerId.equals(task.getOwnerId())) {
            throw BusinessException.notFound("task not found");
        }
        return task;
    }

    public IPage<TaskEntity> pageForOwner(Long ownerId, int page, int size, String status, String type) {
        QueryWrapper<TaskEntity> qw = new QueryWrapper<>();
        qw.eq("owner_id", ownerId);
        if (status != null && !status.isBlank()) qw.eq("status", status);
        if (type != null && !type.isBlank()) qw.eq("type", type);
        qw.orderByDesc("created_at");
        return taskMapper.selectPage(new Page<>(page, Math.min(size, 100)), qw);
    }

    private String abbreviate(String s) {
        return s == null || s.length() <= 8 ? s : s.substring(0, 8) + "...";
    }

    // ---- Spring events consumed by media/render/analysis modules ----
    public record TaskProgressEvent(String taskId, Long ownerId, String type, int progress, String phase) {}
    public record TaskSucceededEvent(String taskId, String type, Long ownerId, String inputRef,
                                     String inputVersion, String outputRef, Object result) {}
    public record TaskFailedEvent(String taskId, String type, Long ownerId, String inputRef,
                                  String errorCode, String errorMessage) {}
    public record TaskCancelledEvent(String taskId, String type, Long ownerId, String inputRef) {}
}
