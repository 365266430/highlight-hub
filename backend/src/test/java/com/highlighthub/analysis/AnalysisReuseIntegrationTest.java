package com.highlighthub.analysis;

import com.highlighthub.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §14: analysis result reuse key (media content + adapter version + config hash + algorithm). */
class AnalysisReuseIntegrationTest extends AbstractIntegrationTest {

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
        jdbc.update("""
                INSERT INTO adapter_definitions (id, game_key, display_name, created_at, updated_at)
                VALUES ('ad-r', 'reuse-game', 'Reuse Game', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        jdbc.update("""
                INSERT INTO adapter_versions (id, adapter_id, adapter_version, template_version, status, created_at)
                VALUES ('av-r', 'ad-r', 1, 't1', 'EXPERIMENTAL', UTC_TIMESTAMP(3))
                """);
        return "av-r";
    }

    private void finishAnalyzeTask(String taskId) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.add("X-Worker-Token", "dev-worker-token-change-me");
        var resp = rest.exchange("/internal/tasks/claim", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("workerId", "w-reuse", "types", List.of("ANALYZE"), "maxCount", 5,
                                "leaseSeconds", 60), h), String.class);
        var tasks = (java.util.List<Map<String, Object>>) com.highlighthub.common.Utils.fromJson(
                resp.getBody(), java.util.List.class);
        String token = (String) tasks.stream().filter(t -> taskId.equals(t.get("taskId")))
                .findFirst().orElseThrow().get("attemptToken");
        rest.exchange("/internal/tasks/" + taskId + "/complete", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(Map.of("attemptToken", token,
                        "result", Map.of("ok", true, "samplesProcessed", 1, "eventsEmitted", 0,
                                "events", List.of())), h), String.class);
    }

    @Test
    void identicalConfigReusesFinishedRun() {
        adapterVersionId();
        String mediaId = seedReadyMedia("reuse1");
        Client c = client();
        c.registerAndLogin("reuse1", "password123");
        Map<String, Object> params = Map.of(
                "sampleIntervalMs", 500,
                "rois", List.of(Map.of("id", "r1", "x", 0.5, "y", 0.1, "w", 0.4, "h", 0.2,
                        "mode", "number", "eventType", "SCORE_CHANGE")));

        ResponseEntity<String> first = c.post("/api/media/" + mediaId + "/analyses",
                Map.of("adapterVersionId", "av-r", "params", params));
        assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(200);
        // (response body kept in the assertion message for diagnosability)
        String run1 = json(first).get("id").toString();

        finishAnalyzeTask(jdbc.queryForObject("SELECT task_id FROM analysis_runs WHERE id=?", String.class, run1));

        // identical request: the finished run is returned, no second task queued
        ResponseEntity<String> second = c.post("/api/media/" + mediaId + "/analyses",
                Map.of("adapterVersionId", "av-r", "params", params));
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(json(second).get("id")).isEqualTo(run1);
        Integer taskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE type='ANALYZE' AND input_ref=?", Integer.class, run1);
        assertThat(taskCount).isEqualTo(1);

        // different config: a NEW run with its own task
        ResponseEntity<String> third = c.post("/api/media/" + mediaId + "/analyses",
                Map.of("adapterVersionId", "av-r", "params",
                        Map.of("sampleIntervalMs", 250,
                                "rois", List.of(Map.of("id", "r1", "x", 0.5, "y", 0.1, "w", 0.4, "h", 0.2,
                                        "mode", "number", "eventType", "SCORE_CHANGE")))));
        assertThat(third.getStatusCode().value()).isEqualTo(200);
        assertThat(json(third).get("id")).isNotEqualTo(run1);
    }
}
