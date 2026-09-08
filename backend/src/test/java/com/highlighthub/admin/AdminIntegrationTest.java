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
