package com.highlighthub.task;

import com.highlighthub.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.highlighthub.common.Utils;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker-facing task queue semantics exercised over the internal HTTP API with
 * the shared worker token (the way the real Python worker talks to Java).
 */
class TaskQueueIntegrationTest extends AbstractIntegrationTest {

    private static final String TOKEN = "dev-worker-token-change-me";

    @org.springframework.beans.factory.annotation.Autowired
    private com.highlighthub.task.TaskService taskService;

    private HttpHeaders workerHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Worker-Token", TOKEN);
        return h;
    }

    private <T> ResponseEntity<T> worker(String method, String path, Object body, Class<T> type) {
        return rest.exchange(path, HttpMethod.valueOf(method),
                new HttpEntity<>(body == null ? null : body, workerHeaders()), type);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> claim(String workerId, List<String> types, int max) {
        ResponseEntity<String> resp = worker("POST", "/internal/tasks/claim",
                Map.of("workerId", workerId, "types", types, "maxCount", max, "leaseSeconds", 60), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return (List<Map<String, Object>>) Utils.fromJson(resp.getBody(), List.class);
    }

    @Test
    void twoWorkersNeverClaimTheSameTask() throws Exception {
        Client c = client();
        c.registerAndLogin("taskuser1", "password123");
        // seed a task directly
        jdbc.update("""
                INSERT INTO tasks (id, owner_id, type, input_ref, status, attempt, max_attempts, progress, next_run_at, created_at, updated_at)
                VALUES ('t-race-1', 1, 'PROBE', 'media-1', 'QUEUED', 0, 3, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<List<Map<String, Object>>> w1 = pool.submit((Callable<List<Map<String, Object>>>) () -> claim("worker-A", List.of("PROBE"), 5));
        Future<List<Map<String, Object>>> w2 = pool.submit((Callable<List<Map<String, Object>>>) () -> claim("worker-B", List.of("PROBE"), 5));
        List<Map<String, Object>> a = w1.get();
        List<Map<String, Object>> b = w2.get();
        pool.shutdownNow();

        String tokenA = a.isEmpty() ? (b.isEmpty() ? null : (String) b.get(0).get("attemptToken"))
                : (String) a.get(0).get("attemptToken");
        int totalClaimed = a.size() + b.size();
        assertThat(totalClaimed).isEqualTo(1);
        Integer runningTasks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE status='RUNNING' AND attempt_token=?", Integer.class, tokenA);
        assertThat(runningTasks).isEqualTo(1);
    }

    @Test
    void staleAttemptTokenCannotCompleteTheTask() {
        jdbc.update("""
                INSERT INTO tasks (id, owner_id, type, input_ref, status, attempt, max_attempts, progress, next_run_at, created_at, updated_at)
                VALUES ('t-stale-1', 1, 'PROBE', 'media-1', 'QUEUED', 0, 3, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        List<Map<String, Object>> first = claim("worker-A", List.of("PROBE"), 5);
        assertThat(first).hasSize(1);
        String oldToken = (String) first.get(0).get("attemptToken");

        // simulate lease loss and reclaim
        jdbc.update("UPDATE tasks SET lease_until = UTC_TIMESTAMP(3) - INTERVAL 10 SECOND WHERE id='t-stale-1'");
        taskService.sweepExpiredLeases();
        // fast-forward the requeue backoff
        jdbc.update("UPDATE tasks SET next_run_at = UTC_TIMESTAMP(3) WHERE id='t-stale-1'");
        List<Map<String, Object>> second = claim("worker-B", List.of("PROBE"), 5);
        assertThat(second).hasSize(1);
        String newToken = (String) second.get(0).get("attemptToken");
        assertThat(newToken).isNotEqualTo(oldToken);

        // the old worker tries to complete: rejected with 409
        ResponseEntity<String> late = worker("POST", "/internal/tasks/t-stale-1/complete",
                Map.of("attemptToken", oldToken, "result", Map.of("ok", true)), String.class);
        assertThat(late.getStatusCode().value()).isEqualTo(409);
        String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id='t-stale-1'", String.class);
        assertThat(status).isEqualTo("RUNNING");

        // the live worker completes successfully
        ResponseEntity<String> ok = worker("POST", "/internal/tasks/t-stale-1/complete",
                Map.of("attemptToken", newToken, "result", Map.of("ok", true)), String.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        status = jdbc.queryForObject("SELECT status FROM tasks WHERE id='t-stale-1'", String.class);
        assertThat(status).isEqualTo("SUCCEEDED");
    }

    @Test
    void successAfterCancelRequestCannotResurrectTheTask() {
        jdbc.update("""
                INSERT INTO tasks (id, owner_id, type, input_ref, status, attempt, max_attempts, progress, next_run_at, created_at, updated_at)
                VALUES ('t-cancel-1', 1, 'RENDER', 'job-1', 'QUEUED', 0, 3, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        List<Map<String, Object>> claimed = claim("worker-A", List.of("RENDER"), 5);
        assertThat(claimed).hasSize(1);
        String token = (String) claimed.get(0).get("attemptToken");

        // user requests cancel while running; the transition mirrors requestCancel()
        jdbc.update("""
                UPDATE tasks SET status='CANCEL_REQUESTED', cancel_requested_at=UTC_TIMESTAMP(3) WHERE id='t-cancel-1'
                """);

        // worker asks about cancellation and sees it
        ResponseEntity<String> cancelState = worker("GET", "/internal/tasks/t-cancel-1/cancellation?attemptToken=" + token,
                null, String.class);
        assertThat(Utils.fromJson(cancelState.getBody(), Map.class).get("cancelRequested")).isEqualTo(true);

        // a late success arrives anyway: accepted as CANCELLED, never resurrected
        ResponseEntity<String> late = worker("POST", "/internal/tasks/t-cancel-1/complete",
                Map.of("attemptToken", token, "result", Map.of("ok", true)), String.class);
        assertThat(late.getStatusCode().value()).isEqualTo(200);
        assertThat(com.highlighthub.common.Utils.fromJson(late.getBody(), Map.class).get("status"))
                .isEqualTo("CANCELLED");
        String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id='t-cancel-1'", String.class);
        assertThat(status).isEqualTo("CANCELLED");
    }

    @Test
    void queuedTaskCancelsImmediately() {
        jdbc.update("""
                INSERT INTO tasks (id, owner_id, type, input_ref, status, attempt, max_attempts, progress, next_run_at, created_at, updated_at)
                VALUES ('t-cancel-2', NULL, 'CLEANUP', 'media-9', 'QUEUED', 0, 3, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        // not logged in: CSRF is checked before authentication, so a token-less POST is 403
        ResponseEntity<String> resp = rest.exchange("/api/tasks/t-cancel-2/cancel", HttpMethod.POST,
                new HttpEntity<>(null, jsonHeaders()), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        // logged-in owner cancels the queued task -> CANCELLED immediately
        Client owner = client();
        owner.fetchCsrf();
        owner.register("canceller", "password123");
        owner.post("/api/auth/login", Map.of("username", "canceller", "password", "password123"));
        jdbc.update("UPDATE tasks SET owner_id = (SELECT id FROM users WHERE username='canceller') WHERE id='t-cancel-2'");
        ResponseEntity<String> okCancel = owner.post("/api/tasks/t-cancel-2/cancel", Map.of());
        assertThat(okCancel.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id='t-cancel-2'", String.class))
                .isEqualTo("CANCELLED");
    }

    @Test
    void leaseSweepRequeuesDeadWorkerTask() {
        jdbc.update("""
                INSERT INTO tasks (id, owner_id, type, input_ref, status, attempt, max_attempts, progress, next_run_at,
                  attempt_token, worker_id, lease_until, started_at, created_at, updated_at)
                VALUES ('t-dead-1', 1, 'PROBE', 'media-1', 'RUNNING', 1, 3, 10, UTC_TIMESTAMP(3),
                  'tok-dead', 'worker-zombie', UTC_TIMESTAMP(3) - INTERVAL 5 SECOND, UTC_TIMESTAMP(3),
                  UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        taskService.sweepExpiredLeases();
        Map<String, Object> task = jdbc.queryForMap("SELECT status, attempt FROM tasks WHERE id='t-dead-1'");
        assertThat(task.get("status")).isEqualTo("QUEUED");
        assertThat(((Number) task.get("attempt")).intValue()).isEqualTo(1);
    }

    @Test
    void retriesExhaustedThenFailed() {
        jdbc.update("""
                INSERT INTO tasks (id, owner_id, type, input_ref, status, attempt, max_attempts, progress, next_run_at, created_at, updated_at)
                VALUES ('t-fail-1', 1, 'RENDER', 'job-1', 'QUEUED', 0, 2, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        // attempt 1 fails retryable -> requeued
        List<Map<String, Object>> a = claim("worker-A", List.of("RENDER"), 5);
        ResponseEntity<String> f1 = worker("POST", "/internal/tasks/t-fail-1/fail",
                Map.of("attemptToken", a.get(0).get("attemptToken"), "errorCode", "FFMPEG_CRASH",
                        "errorMessage", "segfault", "retryable", true), String.class);
        assertThat(f1.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id='t-fail-1'", String.class)).isEqualTo("QUEUED");
        // fast-forward the requeue backoff
        jdbc.update("UPDATE tasks SET next_run_at = UTC_TIMESTAMP(3) WHERE id='t-fail-1'");

        // attempt 2 fails -> maxAttempts reached -> FAILED
        List<Map<String, Object>> b = claim("worker-B", List.of("RENDER"), 5);
        worker("POST", "/internal/tasks/t-fail-1/fail",
                Map.of("attemptToken", b.get(0).get("attemptToken"), "errorCode", "FFMPEG_CRASH",
                        "errorMessage", "segfault again", "retryable", true), String.class);
        Map<String, Object> task = jdbc.queryForMap("SELECT status, error_code FROM tasks WHERE id='t-fail-1'");
        assertThat(task.get("status")).isEqualTo("FAILED");
        assertThat(task.get("error_code")).isEqualTo("FFMPEG_CRASH");
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_attempts WHERE task_id='t-fail-1'", Integer.class);
        assertThat(attempts).isEqualTo(2);
    }

    @Test
    void internalEndpointsRejectMissingWorkerToken() {
        ResponseEntity<String> noToken = rest.exchange("/internal/tasks/claim", HttpMethod.POST,
                new HttpEntity<>(Map.of("workerId", "w", "types", List.of("PROBE"))), String.class);
        assertThat(noToken.getStatusCode().value()).isEqualTo(403);
        ResponseEntity<String> badToken = rest.exchange("/internal/tasks/claim", HttpMethod.POST,
                new HttpEntity<>(Map.of("workerId", "w", "types", List.of("PROBE")),
                        headersWithToken("wrong-token")), String.class);
        assertThat(badToken.getStatusCode().value()).isEqualTo(403);
    }

    private HttpHeaders headersWithToken(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Worker-Token", token);
        return h;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
