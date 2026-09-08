package com.highlighthub.admin;

import com.highlighthub.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminIntegrationTest extends AbstractIntegrationTest {

    private void seedAdmin() {
        jdbc.update("""
                INSERT INTO users (username, password_hash, display_name, role, storage_quota_bytes, used_bytes,
                  status, created_at, updated_at)
                VALUES ('admin', ?, 'Admin', 'ADMIN',
                  2147483648, 0, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, new BCryptPasswordEncoder().encode("password123"));
    }

    private void seedAdapterVersion() {
        jdbc.update("""
                INSERT INTO adapter_definitions (id, game_key, display_name, created_at, updated_at)
                VALUES ('ad-1', 'test-game', 'Test Game', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        jdbc.update("""
                INSERT INTO adapter_versions (id, adapter_id, adapter_version, template_version, status,
                  created_at) VALUES ('av-1', 'ad-1', 1, 't1', 'EXPERIMENTAL', UTC_TIMESTAMP(3))
                """);
    }

    @Test
    void adminEndpointsForbiddenForNormalUsers() {
        Client c = client();
        c.registerAndLogin("plainuser1", "password123");
        assertThat(c.get("/api/admin/stats").getStatusCode().value()).isEqualTo(403);
        assertThat(c.get("/api/admin/users").getStatusCode().value()).isEqualTo(403);
        assertThat(c.get("/api/admin/adapters").getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void statsReturnsMeasuredNumbers() {
        seedAdmin();
        jdbc.update("""
                INSERT INTO tasks (id, owner_id, type, input_ref, status, attempt, max_attempts, progress,
                  next_run_at, created_at, started_at, updated_at)
                VALUES ('t-s1', 1, 'PROBE', 'm-1', 'SUCCEEDED', 1, 3, 100, UTC_TIMESTAMP(3),
                  UTC_TIMESTAMP(3) - INTERVAL 10 SECOND, UTC_TIMESTAMP(3) - INTERVAL 5 SECOND, UTC_TIMESTAMP(3))
                """);
        Client c = client();
        c.registerAndLogin("plainuser2", "password123");
        c.post("/api/auth/login", Map.of("username", "admin", "password", "password123"));
        var resp = c.get("/api/admin/stats");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> stats = json(resp);
        // null numbers are omitted by the non-null JSON policy (e.g. no terminal renders yet)
        assertThat(stats).containsKeys("tasksByStatus", "candidatesByStatus", "users");
        Object rate = stats.get("renderSuccessRate");
        assertThat(rate == null || rate instanceof Number).isTrue();
        List<Map<String, Object>> byStatus = (List<Map<String, Object>>) stats.get("tasksByStatus");
        assertThat(byStatus).isNotEmpty();
    }

    @Test
    void quotaUpdateClampsBelowUsage() {
        seedAdmin();
        jdbc.update("""
                INSERT INTO users (username, password_hash, display_name, role, storage_quota_bytes, used_bytes,
                  status, created_at, updated_at)
                VALUES ('heavy1', 'x', 'h', 'USER', 1000, 800, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        Client c = client();
        c.registerAndLogin("plainuser3", "password123");
        c.post("/api/auth/login", Map.of("username", "admin", "password", "password123"));
        var ok = c.put("/api/admin/users/2/quota", Map.of("quotaBytes", 4096));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        // shrink below current usage is clamped to usage, never below
        var shrink = c.put("/api/admin/users/2/quota", Map.of("quotaBytes", 10));
        assertThat(shrink.getStatusCode().value()).isEqualTo(200);
        assertThat(json(shrink).get("storageQuotaBytes")).isEqualTo(800);
        var bad = c.put("/api/admin/users/2/quota", Map.of("quotaBytes", -5));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void workerLivenessAndRenderRealtimeRatioMeasured() {
        seedAdmin();
        // worker pings through the internal API (service-auth guarded)
        org.springframework.http.HttpHeaders wh = new org.springframework.http.HttpHeaders();
        wh.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        wh.add("X-Worker-Token", "dev-worker-token-change-me");
        var ping = rest.exchange("/internal/tasks/ping", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(Map.of("workerId", "worker-live"), wh), String.class);
        assertThat(ping.getStatusCode().value()).isEqualTo(200);

        // a finished render with measured output duration feeds the realtime ratio
        jdbc.update("""
                INSERT INTO render_jobs (id, project_id, project_revision, owner_id, media_id, preset_version,
                  renderer_version, status, task_id, output_size, output_duration_ms, created_at)
                VALUES ('r-rt', 'p-rt', 1, 1, 'm-rt', 'mp4-h264-aac-v1', 'renderer-2026.09-v2', 'SUCCEEDED',
                  't-rt', 10240, 4000, UTC_TIMESTAMP(3))
                """);
        jdbc.update("""
                INSERT INTO tasks (id, owner_id, type, input_ref, status, attempt, max_attempts, progress,
                  next_run_at, created_at, started_at, finished_at, updated_at)
                VALUES ('t-rt', 1, 'RENDER', 'r-rt', 'SUCCEEDED', 1, 3, 100, UTC_TIMESTAMP(3),
                  UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) - INTERVAL 10 SECOND,
                  UTC_TIMESTAMP(3) - INTERVAL 5 SECOND, UTC_TIMESTAMP(3))
                """);

        Client c = client();
        c.registerAndLogin("plainuser5", "password123");
        c.post("/api/auth/login", Map.of("username", "admin", "password", "password123"));
        Map<String, Object> stats = json(c.get("/api/admin/stats"));

        List<Map<String, Object>> workers = (List<Map<String, Object>>) stats.get("workers");
        assertThat(workers).extracting(w -> w.get("worker_id")).contains("worker-live");
        Map<String, Object> live = workers.stream()
                .filter(w -> "worker-live".equals(w.get("worker_id"))).findFirst().orElseThrow();
        assertThat(((Number) live.get("online")).intValue()).isEqualTo(1);

        List<Map<String, Object>> ratios = (List<Map<String, Object>>) stats.get("renderRealtimeRatio");
        assertThat(ratios).hasSize(1);
        // 5 execution seconds / 4 output seconds = 1.25
        assertThat(((Number) ratios.get(0).get("ratio")).doubleValue()).isEqualTo(1.25);
    }

    @Test
    void adapterStatusLifecycleGatesVerifiedBehindAttestation() {
        seedAdmin();
        seedAdapterVersion();
        Client c = client();
        c.registerAndLogin("plainuser4", "password123");
        c.post("/api/auth/login", Map.of("username", "admin", "password", "password123"));

        // EXPERIMENTAL -> VERIFIED without attestation is refused
        var noAttest = c.put("/api/admin/adapters/versions/av-1/status", Map.of("status", "VERIFIED"));
        assertThat(noAttest.getStatusCode().value()).isEqualTo(400);

        // with attestation it passes
        var verified = c.put("/api/admin/adapters/versions/av-1/status",
                Map.of("status", "VERIFIED", "attestation", "real-game eval: 100 labeled clips, P=0.9 R=0.85"));
        assertThat(verified.getStatusCode().value()).isEqualTo(200);

        // VERIFIED -> DRAFT is not a legal transition
        var illegal = c.put("/api/admin/adapters/versions/av-1/status", Map.of("status", "DRAFT"));
        assertThat(illegal.getStatusCode().value()).isEqualTo(409);
    }
}
