package com.highlighthub.analysis;

import com.highlighthub.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analysis + highlight API integration: runs are driven through the ANALYZE
 * task result path (worker behavior simulated by completing the task exactly
 * as the real Python worker would).
 */
class AnalysisHighlightIntegrationTest extends AbstractIntegrationTest {

    private String seedReadyMedia(String username) {
        Client c = client();
        c.registerAndLogin(username, "password123");
        jdbc.update("""
                INSERT INTO media (id, owner_id, original_filename, content_hash, file_size, storage_key, status,
                  duration_ms, width, height, video_codec, audio_stream_count, rotation, created_at, updated_at)
                VALUES (?, (SELECT id FROM users WHERE username=?), 'clip.mp4', ?, 1000,
                  'original/m-src/source', 'READY', 600000, 1920, 1080, 'h264', 1, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, "m-" + username, username, "hash-" + username);
        return "m-" + username;
    }

    private String adapterVersionId() {
        new com.highlighthub.adapter.GenericAdapterSeeder(
                context.getBean(com.highlighthub.adapter.AdapterDefinitionMapper.class),
                context.getBean(com.highlighthub.adapter.AdapterVersionMapper.class)).seed();
        return jdbc.queryForObject(
                "SELECT av.id FROM adapter_versions av JOIN adapter_definitions ad ON ad.id = av.adapter_id " +
                        "WHERE ad.game_key = 'generic-ocr' LIMIT 1", String.class);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext context;

    private void completeAnalyzeTask(String taskId, Map<String, Object> result) {
        String token = jdbc.queryForObject("SELECT attempt_token FROM tasks WHERE id=?", String.class, taskId);
        // a live worker would claim first; emulate claim to set RUNNING + token
        var claimed = claimTask(taskId);
        token = claimed;
        rest.exchange("/internal/tasks/" + taskId + "/complete", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(org.springframework.http.HttpMethod.POST.name(),
                        workerHeaders(Map.of("attemptToken", token, "result", result))), String.class);
    }

    private org.springframework.http.HttpHeaders workerHeaders(Object body) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.add("X-Worker-Token", "dev-worker-token-change-me");
        h.add("X-Body", "unused");
        return h;
    }

    private String claimTask(String taskId) {
        // claim via internal API (any type) then verify our task got claimed
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.add("X-Worker-Token", "dev-worker-token-change-me");
        var resp = rest.exchange("/internal/tasks/claim", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("workerId", "test-worker", "types", List.of("ANALYZE"), "maxCount", 5,
                                "leaseSeconds", 60), h), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        var tasks = (java.util.List<Map<String, Object>>) com.highlighthub.common.Utils.fromJson(
                resp.getBody(), java.util.List.class);
        return (String) tasks.stream().filter(t -> taskId.equals(t.get("taskId")))
                .findFirst().orElseThrow(() -> new AssertionError("task not claimed"))
                .get("attemptToken");
    }

    @Test
    void autoAnalysisPersistsEventsAndCandidatesFlow() {
        String mediaId = seedReadyMedia("analyst1");
        Client c = client();
        c.registerAndLogin("analyst1", "password123");

        ResponseEntity<String> created = c.post("/api/media/" + mediaId + "/analyses",
                Map.of("adapterVersionId", adapterVersionId()));
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        String runId = json(created).get("id").toString();

        // complete the ANALYZE task exactly as the worker would
        String taskId = jdbc.queryForObject("SELECT task_id FROM analysis_runs WHERE id=?", String.class, runId);
        String token = claimTask(taskId);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.add("X-Worker-Token", "dev-worker-token-change-me");
        Map<String, Object> result = Map.of(
                "ok", true, "samplesProcessed", 100, "eventsEmitted", 3,
                "events", List.of(
                        Map.of("type", "ELIMINATION_NOTICE", "startMs", 120000, "endMs", 121000,
                                "confidence", 0.91, "attributes", Map.of("from", "10", "to", "11")),
                        Map.of("type", "ELIMINATION_NOTICE", "startMs", 128000, "endMs", 129000,
                                "confidence", 0.88, "attributes", Map.of("from", "11", "to", "12")),
                        Map.of("type", "ELIMINATION_NOTICE", "startMs", 133000, "endMs", 134000,
                                "confidence", 0.93, "attributes", Map.of("from", "12", "to", "13"))));
        var resp = rest.exchange("/internal/tasks/" + taskId + "/complete",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(Map.of("attemptToken", token, "result", result),
                        headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> events = jsonList(c.get("/api/analyses/" + runId + "/events"));
        assertThat(events).hasSize(3);
        assertThat(events.get(0).get("source")).isEqualTo("AUTO");
        // spec: ELIMINATION_NOTICE means observed notice; no actor is invented
        assertThat(events.get(0).containsKey("actor")).isFalse();

        // ---- highlight run with default-like params ----
        ResponseEntity<String> run = c.post("/api/analyses/" + runId + "/highlight-runs",
                Map.of("eventType", "ELIMINATION_NOTICE", "windowMs", 20000, "minimumCount", 3,
                        "paddingBeforeMs", 12000, "paddingAfterMs", 8000,
                        "mergeGapMs", 2000, "maxSegmentDurationMs", 90000));
        assertThat(run.getStatusCode().value()).isEqualTo(200);
        String highlightRunId = json(run).get("id").toString();

        List<Map<String, Object>> candidates = jsonList(
                c.get("/api/highlight-runs/" + highlightRunId + "/candidates"));
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("reasonCode")).isEqualTo("EVENT_CLUSTER");
        assertThat((Integer) candidates.get(0).get("startMs")).isEqualTo(108000);
        assertThat((Integer) candidates.get(0).get("endMs")).isEqualTo(142000);

        // accept then use it as project base
        String candidateId = candidates.get(0).get("id").toString();
        ResponseEntity<String> decided = c.patch("/api/highlight-candidates/" + candidateId,
                Map.of("status", "ACCEPTED"));
        assertThat(decided.getStatusCode().value()).isEqualTo(200);
        assertThat(json(decided).get("status")).isEqualTo("ACCEPTED");

        // rerun with different rule params: new candidates, old ones untouched
        ResponseEntity<String> run2 = c.post("/api/analyses/" + runId + "/highlight-runs",
                Map.of("eventType", "ELIMINATION_NOTICE", "windowMs", 5000, "minimumCount", 3,
                        "paddingBeforeMs", 1000, "paddingAfterMs", 1000,
                        "mergeGapMs", 500, "maxSegmentDurationMs", 90000));
        assertThat(run2.getStatusCode().value()).isEqualTo(200);
        String run2Id = json(run2).get("id").toString();
        List<Map<String, Object>> candidates2 = jsonList(
                c.get("/api/highlight-runs/" + run2Id + "/candidates"));
        assertThat(candidates2).isEmpty(); // 3 events span 13s > 5s window
        // first run's candidate still ACCEPTED
        List<Map<String, Object>> candidates1 = jsonList(
                c.get("/api/highlight-runs/" + highlightRunId + "/candidates"));
        assertThat(candidates1.get(0).get("status")).isEqualTo("ACCEPTED");
    }

    @Test
    void unsupportedInputFailsAnalysisWithClearMessage() {
        String mediaId = seedReadyMedia("analyst2");
        Client c = client();
        c.registerAndLogin("analyst2", "password123");
        ResponseEntity<String> created = c.post("/api/media/" + mediaId + "/analyses",
                Map.of("adapterVersionId", adapterVersionId()));
        String runId = json(created).get("id").toString();
        String taskId = jdbc.queryForObject("SELECT task_id FROM analysis_runs WHERE id=?", String.class, runId);
        String token = claimTask(taskId);
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.add("X-Worker-Token", "dev-worker-token-change-me");
        rest.exchange("/internal/tasks/" + taskId + "/fail", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(Map.of("attemptToken", token,
                        "errorCode", "ADAPTER_INPUT_UNSUPPORTED",
                        "errorMessage", "input not supported by adapter; recalibrate or use manual mode",
                        "retryable", false), headers), String.class);
        Map<String, Object> run = json(c.get("/api/analyses/" + runId));
        assertThat(run.get("status")).isEqualTo("FAILED");
        // no events persisted
        assertThat(jsonList(c.get("/api/analyses/" + runId + "/events"))).isEmpty();
    }

    @Test
    void crossUserAnalysisAccessBlocked() {
        String mediaId = seedReadyMedia("analyst3");
        Client c = client();
        c.registerAndLogin("analyst3", "password123");
        ResponseEntity<String> created = c.post("/api/media/" + mediaId + "/analyses",
                Map.of("adapterVersionId", adapterVersionId()));
        String runId = json(created).get("id").toString();
        Client other = client();
        other.registerAndLogin("analyst4", "password123");
        assertThat(other.get("/api/analyses/" + runId).getStatusCode().value()).isEqualTo(404);
        assertThat(other.get("/api/analyses/" + runId + "/events").getStatusCode().value()).isEqualTo(404);
    }
}
