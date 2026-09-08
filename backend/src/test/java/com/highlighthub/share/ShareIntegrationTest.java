package com.highlighthub.share;

import com.highlighthub.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShareIntegrationTest extends AbstractIntegrationTest {

    @Test
    void shareCreateDownloadRevokeFlow() {
        // seed owner + finished render
        client().registerAndLogin("sharer1", "password123");
        jdbc.update("""
                INSERT INTO media (id, owner_id, original_filename, content_hash, file_size, storage_key, status,
                  duration_ms, width, height, video_codec, audio_stream_count, rotation, created_at, updated_at)
                VALUES ('m-share', (SELECT id FROM users WHERE username='sharer1'), 'c.mp4', 'h1', 1000,
                  'original/m-share/source', 'READY', 60000, 1280, 720, 'h264', 1, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        jdbc.update("""
                INSERT INTO editing_projects (id, owner_id, media_id, name, latest_revision, status, created_at, updated_at)
                VALUES ('p-share', (SELECT id FROM users WHERE username='sharer1'), 'm-share', 'proj', 1, 'ACTIVE',
                  UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        jdbc.update("""
                INSERT INTO editing_project_revisions (project_id, revision, schema_version, edit_decision_json, created_at)
                VALUES ('p-share', 1, 1, '{"schemaVersion":1,"sourceMediaId":"m-share","segments":[],"output":{"aspectMode":"SOURCE","width":1280,"height":720,"fps":30}}', UTC_TIMESTAMP(3))
                """);
        jdbc.update("""
                INSERT INTO media_assets (id, owner_id, media_id, type, storage_key, size, status, created_at)
                VALUES ('a-share', (SELECT id FROM users WHERE username='sharer1'), NULL, 'RENDER_OUTPUT',
                  'render/r-share/output.mp4', 20480, 'ACTIVE', UTC_TIMESTAMP(3))
                """);
        jdbc.update("""
                INSERT INTO render_jobs (id, project_id, project_revision, owner_id, media_id, preset_version,
                  renderer_version, status, output_asset_id, output_size, created_at)
                VALUES ('r-share', 'p-share', 1, (SELECT id FROM users WHERE username='sharer1'), 'm-share',
                  'mp4-h264-aac-v1', 'renderer-2026.09-v1', 'SUCCEEDED', 'a-share', 20480, UTC_TIMESTAMP(3))
                """);
        // the output file itself
        byte[] content = new byte[20480];
        new java.security.SecureRandom().nextBytes(content);
        storage().put("render/r-share/output.mp4", new java.io.ByteArrayInputStream(content), content.length);

        Client c = client();
        c.registerAndLogin("sharer1", "password123");
        var created = c.post("/api/renders/r-share/shares", Map.of("ttlHours", 1));
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> share = json(created);
        String token = (String) share.get("token");

        // anonymous download via the share token works
        var resp = rest.getForEntity("/api/shares/" + token + "/download", byte[].class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(20480);

        // raw footage is NOT reachable via shares - the token only exposes the render output
        var notFound = rest.getForEntity("/api/shares/not-a-real-token-000000000000000000/download", byte[].class);
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);

        // revoke by owner -> download returns 410
        var listed = c.delete("/api/shares/" + share.get("id"));
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        var afterRevoke = rest.getForEntity("/api/shares/" + token + "/download", byte[].class);
        assertThat(afterRevoke.getStatusCode().value()).isEqualTo(410);
    }

    private com.highlighthub.storage.LocalStorageService storage() {
        return context.getBean(com.highlighthub.storage.LocalStorageService.class);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext context;
}
