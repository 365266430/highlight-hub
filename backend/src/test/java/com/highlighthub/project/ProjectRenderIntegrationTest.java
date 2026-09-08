package com.highlighthub.project;

import com.highlighthub.AbstractIntegrationTest;
import com.highlighthub.common.Utils;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectRenderIntegrationTest extends AbstractIntegrationTest {

    private String seedReadyMedia(Client c, String username) {
        c.registerAndLogin(username, "password123");
        jdbc.update("""
                INSERT INTO media (id, owner_id, original_filename, content_hash, file_size, storage_key, status,
                  duration_ms, width, height, video_codec, audio_stream_count, rotation, created_at, updated_at)
                VALUES (?, (SELECT id FROM users WHERE username=?), 'clip.mp4', ?, 1000,
                  'original/m-1/source', 'READY', 600000, 1920, 1080, 'h264', 1, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, "m-" + username, username, "hash-" + username);
        return "m-" + username;
    }

    private Map<String, Object> createProject(Client c, String mediaId) {
        ResponseEntity<String> resp = c.post("/api/projects", Map.of("mediaId", mediaId, "name", "test project"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return json(resp);
    }

    private Map<String, Object> edlBody(String mediaId, int expectedRevision, long in, long out) {
        return Map.of(
                "expectedRevision", expectedRevision,
                "name", "test project",
                "schemaVersion", 1,
                "sourceMediaId", mediaId,
                "segments", List.of(Map.of("id", "s1", "sourceInMs", in, "sourceOutMs", out,
                        "caption", "这波配合成功了", "sourceVolume", 1.0)),
                "output", Map.of("aspectMode", "SOURCE", "width", 1920, "height", 1080, "fps", 30));
    }

    @Test
    void saveCreatesImmutableRevisionsAndConflictsOnStaleRevision() {
        String mediaId = seedReadyMedia(client(), "projuser1");
        Client c = client();
        c.registerAndLogin("projuser1", "password123");
        Map<String, Object> project = createProject(c, mediaId);
        String projectId = (String) project.get("id");

        ResponseEntity<String> save1 = c.put("/api/projects/" + projectId, edlBody(mediaId, 0, 125000, 153000));
        assertThat(save1.getStatusCode().value()).isEqualTo(200);
        assertThat(json(save1).get("revision")).isEqualTo(1);

        // stale expectedRevision -> 409, no silent overwrite
        ResponseEntity<String> stale = c.put("/api/projects/" + projectId, edlBody(mediaId, 0, 1000, 2000));
        assertThat(stale.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> save2 = c.put("/api/projects/" + projectId, edlBody(mediaId, 1, 1000, 2000));
        assertThat(save2.getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> revisions = jsonList(c.get("/api/projects/" + projectId + "/revisions"));
        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(0).get("revision")).isEqualTo(2);
        // revision 1 is immutable and still fetchable
        Map<String, Object> rev1 = json(c.get("/api/projects/" + projectId + "/revisions/1"));
        Map<String, Object> edl = (Map<String, Object>) rev1.get("edl");
        List<Map<String, Object>> segments = (List<Map<String, Object>>) edl.get("segments");
        assertThat(((Number) segments.get(0).get("sourceInMs")).longValue()).isEqualTo(125000);
    }

    @Test
    void invalidTimeRangesRejected() {
        String mediaId = seedReadyMedia(client(), "projuser2");
        Client c = client();
        c.registerAndLogin("projuser2", "password123");
        String projectId = (String) createProject(c, mediaId).get("id");

        // out of media duration (10 min)
        ResponseEntity<String> tooFar = c.put("/api/projects/" + projectId, edlBody(mediaId, 0, 0, 700000));
        assertThat(tooFar.getStatusCode().value()).isEqualTo(400);
        // inverted range
        ResponseEntity<String> inverted = c.put("/api/projects/" + projectId, edlBody(mediaId, 0, 5000, 4000));
        assertThat(inverted.getStatusCode().value()).isEqualTo(400);
        // volume outside [0, 1.5] rejected
        Map<String, Object> body = new java.util.HashMap<>(edlBody(mediaId, 0, 1000, 2000));
        Map<String, Object> segment = new java.util.HashMap<>(
                (Map<String, Object>) ((List<?>) body.get("segments")).get(0));
        segment.put("sourceVolume", 9.0);
        body.put("segments", List.of(segment));
        ResponseEntity<String> badVolume = c.put("/api/projects/" + projectId, body);
        assertThat(badVolume.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void crossUserProjectAccessIsNotFound() {
        String mediaId = seedReadyMedia(client(), "projuser3");
        Client c = client();
        c.registerAndLogin("projuser3", "password123");
        String projectId = (String) createProject(c, mediaId).get("id");

        Client other = client();
        other.registerAndLogin("otheruser4", "password123");
        assertThat(other.get("/api/projects/" + projectId).getStatusCode().value()).isEqualTo(404);
        assertThat(other.put("/api/projects/" + projectId, edlBody(mediaId, 0, 1, 2)).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void renderSubmitsBoundToRevisionWithIdempotencyKey() {
        String mediaId = seedReadyMedia(client(), "renderuser1");
        Client c = client();
        c.registerAndLogin("renderuser1", "password123");
        String projectId = (String) createProject(c, mediaId).get("id");
        c.put("/api/projects/" + projectId, edlBody(mediaId, 0, 125000, 153000));

        ResponseEntity<String> submit1 = c.post("/api/projects/" + projectId + "/renders", Map.of());
        assertThat(submit1.getStatusCode().value()).isEqualTo(200);
        String renderId1 = (String) json(submit1).get("id");
        Integer revision1 = ((Number) json(submit1).get("projectRevision")).intValue();
        assertThat(revision1).isEqualTo(1);

        // render row references a real task with the immutable EDL in its payload
        Map<String, Object> task = jdbc.queryForMap(
                "SELECT payload_json, type, status FROM tasks WHERE id=(SELECT task_id FROM render_jobs WHERE id=?)",
                renderId1);
        assertThat(task.get("type")).isEqualTo("RENDER");
        Map<String, Object> payload = Utils.fromJson((String) task.get("payload_json"), Map.class);
        assertThat(payload.get("outputKey")).isEqualTo("render/" + renderId1 + "/output.mp4");

        // edit the project (bump to revision 2); the submitted render must not change
        c.put("/api/projects/" + projectId, edlBody(mediaId, 1, 1000, 2000));
        Map<String, Object> job = json(c.get("/api/renders/" + renderId1));
        assertThat(((Number) job.get("projectRevision")).intValue()).isEqualTo(1);
    }

    @Test
    void idempotencyKeyReplaysSameRender() {
        String mediaId = seedReadyMedia(client(), "renderuser2");
        Client c = client();
        c.registerAndLogin("renderuser2", "password123");
        String projectId = (String) createProject(c, mediaId).get("id");
        c.put("/api/projects/" + projectId, edlBody(mediaId, 0, 125000, 153000));

        // NOTE: the idempotency key travels via the Idempotency-Key header; the test client
        // cannot set arbitrary headers through its helper, so exercise the replay path
        // through two sequential submissions with the header set on the raw exchange.
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.add(org.springframework.http.HttpHeaders.COOKIE, String.join("; ", c.cookies));
        h.add("X-XSRF-TOKEN", c.csrfToken);
        h.add("Idempotency-Key", "render-key-42");
        org.springframework.http.HttpEntity<Object> entity = new org.springframework.http.HttpEntity<>(Map.of(), h);
        ResponseEntity<String> r1 = rest.postForEntity("/api/projects/" + projectId + "/renders", entity, String.class);
        ResponseEntity<String> r2 = rest.postForEntity("/api/projects/" + projectId + "/renders", entity, String.class);
        assertThat(r1.getStatusCode().value()).isEqualTo(200);
        assertThat(r2.getStatusCode().value()).isEqualTo(200);
        assertThat(json(r2).get("id")).isEqualTo(json(r1).get("id"));
        Integer jobCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM render_jobs WHERE project_id=?", Integer.class, projectId);
        assertThat(jobCount).isEqualTo(1);
    }

    @Test
    void privateStorageKeyNeverLeakedInApiResponses() {
        String mediaId = seedReadyMedia(client(), "renderuser3");
        Client c = client();
        c.registerAndLogin("renderuser3", "password123");
        String projectId = (String) createProject(c, mediaId).get("id");
        c.put("/api/projects/" + projectId, edlBody(mediaId, 0, 125000, 153000));
        ResponseEntity<String> render = c.post("/api/projects/" + projectId + "/renders", Map.of());
        String body = render.getBody();
        assertThat(body).doesNotContain("original/");
        assertThat(body).doesNotContain(".part-");
        assertThat(body).doesNotContain("/source");
        String mediaBody = c.get("/api/media/" + mediaId).getBody();
        assertThat(mediaBody).doesNotContain("storage_key");
        assertThat(mediaBody).doesNotContain("original/");
    }

    @Test
    void crossUserRenderDownloadForbidden() {
        String mediaId = seedReadyMedia(client(), "renderuser4");
        Client c = client();
        c.registerAndLogin("renderuser4", "password123");
        String projectId = (String) createProject(c, mediaId).get("id");
        c.put("/api/projects/" + projectId, edlBody(mediaId, 0, 125000, 153000));
        String renderId = (String) json(c.post("/api/projects/" + projectId + "/renders", Map.of())).get("id");

        // not finished yet -> 409 for owner
        assertThat(c.get("/api/renders/" + renderId + "/download").getStatusCode().value()).isEqualTo(409);

        Client other = client();
        other.registerAndLogin("otheruser5", "password123");
        assertThat(other.get("/api/renders/" + renderId).getStatusCode().value()).isEqualTo(404);
        assertThat(other.get("/api/renders/" + renderId + "/download").getStatusCode().value()).isEqualTo(404);
    }
}
