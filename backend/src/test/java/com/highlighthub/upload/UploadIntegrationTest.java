package com.highlighthub.upload;

import com.highlighthub.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class UploadIntegrationTest extends AbstractIntegrationTest {

    private static final int CHUNK = 8 * 1024 * 1024; // 8 MiB matches the negotiated chunk size for small files

    private Map<String, Object> createSession(Client c, String filename, long size) {
        ResponseEntity<String> resp = c.post("/api/uploads", Map.of("originalFilename", filename, "declaredSize", size));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return json(resp);
    }

    private byte[] makeChunk(int size, int seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + seed) & 0xff);
        }
        return data;
    }

    @Test
    void fullUploadThenCompleteIsIdempotent() {
        Client c = client();
        c.registerAndLogin("uploader1", "password123");
        long declared = (long) CHUNK + 12345;
        Map<String, Object> session = createSession(c, "game-clip.mp4", declared);
        String uploadId = (String) session.get("uploadId");
        long chunkSize = ((Number) session.get("chunkSize")).longValue();
        int chunkCount = ((Number) session.get("expectedChunkCount")).intValue();
        assertThat(chunkCount).isEqualTo(2);

        // resume query before any chunk
        Map<String, Object> before = json(c.get("/api/uploads/" + uploadId));
        assertThat(((List<?>) before.get("receivedChunks"))).isEmpty();

        byte[] c0 = makeChunk((int) chunkSize, 1);
        byte[] c1 = makeChunk(12345, 2);
        assertThat(c.putRaw("/api/uploads/" + uploadId + "/chunks/0", c0).getStatusCode().value()).isEqualTo(200);
        assertThat(c.putRaw("/api/uploads/" + uploadId + "/chunks/1", c1).getStatusCode().value()).isEqualTo(200);

        Map<String, Object> completed = json(c.post("/api/uploads/" + uploadId + "/complete", Map.of()));
        String mediaId = (String) completed.get("mediaId");
        assertThat(mediaId).isNotBlank();

        // repeated complete returns the same media, never a second one
        Map<String, Object> again = json(c.post("/api/uploads/" + uploadId + "/complete", Map.of()));
        assertThat(again.get("mediaId")).isEqualTo(mediaId);
        assertThat(again.get("alreadyCompleted")).isEqualTo(true);
        Integer mediaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM media WHERE owner_id = (SELECT id FROM users WHERE username='uploader1')",
                Integer.class);
        assertThat(mediaCount).isEqualTo(1);

        // media is created in PROBING state with a PROBE task queued
        Map<String, Object> media = json(c.get("/api/media/" + mediaId));
        assertThat(media.get("status")).isEqualTo("PROBING");
        assertThat(media.get("originalFilename")).isEqualTo("game-clip.mp4");
        Integer probeTasks = jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE type='PROBE' AND input_ref=?",
                Integer.class, mediaId);
        assertThat(probeTasks).isEqualTo(1);
    }

    @Test
    void duplicateChunkSameContentAcceptedDifferentContentConflicts() {
        Client c = client();
        c.registerAndLogin("uploader2", "password123");
        Map<String, Object> session = createSession(c, "a.mp4", CHUNK * 2L);
        String uploadId = (String) session.get("uploadId");
        byte[] data = makeChunk(CHUNK, 7);
        assertThat(c.putRaw("/api/uploads/" + uploadId + "/chunks/0", data).getStatusCode().value()).isEqualTo(200);
        // same content again: accepted as duplicate
        ResponseEntity<String> dup = c.putRaw("/api/uploads/" + uploadId + "/chunks/0", data);
        assertThat(dup.getStatusCode().value()).isEqualTo(200);
        assertThat(json(dup).get("duplicate")).isEqualTo(true);
        // different content, same index: 409
        byte[] other = makeChunk(CHUNK, 8);
        ResponseEntity<String> conflict = c.putRaw("/api/uploads/" + uploadId + "/chunks/0", other);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void completeWithMissingChunksRejected() {
        Client c = client();
        c.registerAndLogin("uploader3", "password123");
        Map<String, Object> session = createSession(c, "b.mp4", CHUNK * 3L);
        String uploadId = (String) session.get("uploadId");
        c.putRaw("/api/uploads/" + uploadId + "/chunks/0", makeChunk(CHUNK, 3));
        // chunk 1 missing
        c.putRaw("/api/uploads/" + uploadId + "/chunks/2", makeChunk(CHUNK, 4));
        ResponseEntity<String> resp = c.post("/api/uploads/" + uploadId + "/complete", Map.of());
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(json(resp).get("code")).isEqualTo("CHUNKS_INCOMPLETE");
    }

    @Test
    void resumeOnlyUploadsMissingChunks() {
        Client c = client();
        c.registerAndLogin("uploader4", "password123");
        Map<String, Object> session = createSession(c, "c.mp4", CHUNK * 2L);
        String uploadId = (String) session.get("uploadId");
        c.putRaw("/api/uploads/" + uploadId + "/chunks/0", makeChunk(CHUNK, 11));
        Map<String, Object> status = json(c.get("/api/uploads/" + uploadId));
        assertThat((List<Integer>) (Object) status.get("receivedChunks")).containsExactly(0);
        // simulate reconnect: fetch received list, upload only the missing one
        c.putRaw("/api/uploads/" + uploadId + "/chunks/1", makeChunk(CHUNK, 12));
        Map<String, Object> done = json(c.post("/api/uploads/" + uploadId + "/complete", Map.of()));
        assertThat(done.get("mediaId")).isNotNull();
    }

    @Test
    void crossUserAccessIsNotFound() {
        Client c1 = client();
        c1.registerAndLogin("owner5", "password123");
        Map<String, Object> session = createSession(c1, "private.mp4", CHUNK);
        String uploadId = (String) session.get("uploadId");

        Client c2 = client();
        c2.registerAndLogin("attacker6", "password123");
        assertThat(c2.get("/api/uploads/" + uploadId).getStatusCode().value()).isEqualTo(404);
        assertThat(c2.putRaw("/api/uploads/" + uploadId + "/chunks/0", makeChunk(1024, 1))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(c2.post("/api/uploads/" + uploadId + "/complete", Map.of()).getStatusCode().value()).isEqualTo(404);
        // the owner can still use the session
        assertThat(c1.putRaw("/api/uploads/" + uploadId + "/chunks/0", makeChunk(CHUNK, 1))
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void pathTraversalFilenameIsSanitized() {
        Client c = client();
        c.registerAndLogin("uploader7", "password123");
        Map<String, Object> session = createSession(c, "..\\..\\..\\windows\\evil.mp4", CHUNK);
        assertThat((String) session.get("uploadId")).isNotBlank();
        // the display filename is sanitized but never becomes a filesystem path
        String stored = jdbc.queryForObject("SELECT original_filename FROM upload_sessions WHERE id=?",
                String.class, session.get("uploadId"));
        assertThat(stored).doesNotContain("..");
    }

    @Test
    void quotaBlocksOversizedReservation() throws Exception {
        Client c = client();
        c.registerAndLogin("poor8", "password123");
        // default dev quota is 2 GiB; declare a file far beyond it
        ResponseEntity<String> resp = c.post("/api/uploads",
                Map.of("originalFilename", "huge.mp4", "declaredSize", 9_000_000_000L));
        assertThat(resp.getStatusCode().value()).isEqualTo(413);
        // one user, many concurrent sessions: the quota cannot be bypassed
        c.registerAndLogin("racer9", "password123");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(pool.submit((Callable<Boolean>) () -> {
                Client cc = client();
                cc.fetchCsrf();
                cc.post("/api/auth/login", Map.of("username", "racer9", "password", "password123"));
                // quota 2GiB; a second 1.5GiB session cannot fit alongside the first
                ResponseEntity<String> r = cc.post("/api/uploads",
                        Map.of("originalFilename", "race.mp4", "declaredSize", 1_500_000_000L));
                return r.getStatusCode().value() == 200;
            }));
        }
        int created = 0;
        for (Future<Boolean> f : results) {
            try {
                if (f.get()) created++;
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        Integer usedSum = jdbc.queryForObject(
                "SELECT used_bytes FROM users WHERE username='racer9'", Integer.class);
        assertThat(created).isEqualTo(1); // second concurrent reservation must fail
        assertThat(usedSum).isEqualTo(1_500_000_000);
    }

    @Test
    void concurrentCompleteCreatesOneMedia() throws Exception {
        Client c = client();
        c.registerAndLogin("concurrent10", "password123");
        Map<String, Object> session = createSession(c, "race.mp4", CHUNK);
        String uploadId = (String) session.get("uploadId");
        c.putRaw("/api/uploads/" + uploadId + "/chunks/0", makeChunk(CHUNK, 21));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ResponseEntity<String>>> completions = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            completions.add(pool.submit(() -> c.post("/api/uploads/" + uploadId + "/complete", Map.of())));
        }
        int succeeded = 0;
        int conflicts = 0;
        String mediaId = null;
        for (Future<ResponseEntity<String>> f : completions) {
            ResponseEntity<String> resp = f.get();
            if (resp.getStatusCode().value() == 200) {
                succeeded++;
                if (mediaId == null) mediaId = (String) json(resp).get("mediaId");
                else assertThat(json(resp).get("mediaId")).isEqualTo(mediaId);
            } else if (resp.getStatusCode().value() == 409) {
                conflicts++;
            }
        }
        pool.shutdownNow();
        Integer mediaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM media WHERE owner_id = (SELECT id FROM users WHERE username='concurrent10')",
                Integer.class);
        assertThat(mediaCount).isEqualTo(1);
        assertThat(succeeded + conflicts).isEqualTo(2);
        assertThat(succeeded).isGreaterThanOrEqualTo(1);
    }

    @Test
    void chunkIndexOutOfRangeRejected() {
        Client c = client();
        c.registerAndLogin("range11", "password123");
        Map<String, Object> session = createSession(c, "d.mp4", CHUNK);
        ResponseEntity<String> resp = c.putRaw("/api/uploads/" + (String) session.get("uploadId") + "/chunks/5",
                makeChunk(1024, 5));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void cancelReleasesQuotaAndChunks() {
        Client c = client();
        c.registerAndLogin("cancel12", "password123");
        Map<String, Object> session = createSession(c, "e.mp4", CHUNK);
        String uploadId = (String) session.get("uploadId");
        c.putRaw("/api/uploads/" + uploadId + "/chunks/0", makeChunk(CHUNK, 31));
        ResponseEntity<String> cancelled = c.delete("/api/uploads/" + uploadId);
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        Integer used = jdbc.queryForObject(
                "SELECT used_bytes FROM users WHERE username='cancel12'", Integer.class);
        assertThat(used).isZero();
        Integer chunkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM upload_chunks WHERE upload_id=?", Integer.class, uploadId);
        assertThat(chunkCount).isZero();
        Map<String, Object> status = json(c.get("/api/uploads/" + uploadId));
        assertThat(status.get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void unauthenticatedUploadsRejected() {
        Client c = client();
        c.fetchCsrf();
        assertThat(c.post("/api/uploads", Map.of("declaredSize", 100)).getStatusCode().value()).isEqualTo(401);
    }
}
