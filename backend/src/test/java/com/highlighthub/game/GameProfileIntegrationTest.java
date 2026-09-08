package com.highlighthub.game;

import com.highlighthub.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GameProfileIntegrationTest extends AbstractIntegrationTest {

    @Test
    void genreCatalogCoversMajorTypes() {
        Client c = client();
        c.registerAndLogin("gamer0", "password123");
        List<Map<String, Object>> genres = jsonList(c.get("/api/games/genres"));
        List<String> keys = genres.stream().map(g -> g.get("key").toString()).toList();
        List<String> expectedKeys = List.of("moba", "fps", "tps", "battle-royale", "rts", "racing",
                "sports", "fighting", "card-battler", "mmo", "sandbox-survival", "action-soulslike",
                "roguelike", "openworld-arpg", "simulation", "rhythm", "horror-puzzle",
                "platformer", "strategy-tbs", "party-casual", "other");
        for (String expected : expectedKeys) {
            org.assertj.core.api.Assertions.assertThat(keys.contains(expected)).isTrue();
        }
        // FPS preset carries the killfeed guidance
        Map<String, Object> fps = (Map<String, Object>) genres.stream()
                .filter(g -> g.get("key").equals("fps")).findFirst().orElseThrow();
        assertThat(fps.get("typicalEvents").toString()).contains("ELIMINATION_NOTICE");
    }

    @Test
    void crudWithOwnershipAndValidation() {
        Client c = client();
        c.registerAndLogin("gamer1", "password123");
        List<Map<String, Object>> rois = List.of(Map.of("id", "killfeed", "x", 0.6, "y", 0.02, "w", 0.38,
                "h", 0.25, "mode", "text", "eventType", "ELIMINATION_NOTICE"));

        var created = c.post("/api/games", Map.of(
                "displayName", "无畏契约-我的HUD", "genre", "fps",
                "defaultRois", rois, "notes", "1440p 校准"));
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        String gameId = json(created).get("id").toString();
        assertThat(json(created).get("genre")).isEqualTo("fps");

        // unknown genre rejected
        var badGenre = c.post("/api/games", Map.of("displayName", "x", "genre", "not-a-genre"));
        assertThat(badGenre.getStatusCode().value()).isEqualTo(400);
        // out-of-frame ROI rejected
        var badRoi = c.post("/api/games", Map.of("displayName", "x", "genre", "fps",
                "defaultRois", List.of(Map.of("id", "r", "x", 0.8, "y", 0.8, "w", 0.5, "h", 0.5))));
        assertThat(badRoi.getStatusCode().value()).isEqualTo(400);

        // update
        var updated = c.put("/api/games/" + gameId, Map.of(
                "displayName", "无畏契约-新HUD", "genre", "fps", "defaultRois", rois));
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(json(updated).get("displayName")).isEqualTo("无畏契约-新HUD");

        // cross-user profile is invisible
        Client other = client();
        other.registerAndLogin("gamer2", "password123");
        assertThat(other.get("/api/games").getBody()).doesNotContain(gameId);
        assertThat(other.put("/api/games/" + gameId,
                Map.of("displayName", "hijack", "genre", "fps")).getStatusCode().value()).isEqualTo(404);
        assertThat(other.delete("/api/games/" + gameId).getStatusCode().value()).isEqualTo(404);

        // delete by owner
        assertThat(c.delete("/api/games/" + gameId).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_games WHERE id=?", Integer.class, gameId))
                .isZero();
    }

    @Test
    void analysisUsesProfileRoisAndReuseKeyIncludesThem() {
        Client c = client();
        c.registerAndLogin("gamer3", "password123");
        jdbc.update("""
                INSERT INTO media (id, owner_id, original_filename, content_hash, file_size, storage_key, status,
                  duration_ms, width, height, video_codec, audio_stream_count, rotation, created_at, updated_at)
                VALUES ('m-game', (SELECT id FROM users WHERE username='gamer3'), 'c.mp4', 'hg', 1000,
                  'original/m-game/source', 'READY', 600000, 1920, 1080, 'h264', 1, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        jdbc.update("""
                INSERT INTO adapter_definitions (id, game_key, display_name, created_at, updated_at)
                VALUES ('ad-g', 'generic-ocr2', 'g2', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
        jdbc.update("""
                INSERT INTO adapter_versions (id, adapter_id, adapter_version, template_version, status, created_at)
                VALUES ('av-g', 'ad-g', 1, 't1', 'EXPERIMENTAL', UTC_TIMESTAMP(3))
                """);
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = json(c.post("/api/games", Map.of(
                "displayName", "CS2", "genre", "fps",
                "defaultRois", List.of(Map.of("id", "kf", "x", 0.6, "y", 0.02, "w", 0.38, "h", 0.25,
                        "mode", "text", "eventType", "ELIMINATION_NOTICE")))));

        var run1 = c.post("/api/media/m-game/analyses",
                Map.of("adapterVersionId", "av-g", "gameId", profile.get("id")));
        assertThat(run1.getStatusCode().value()).isEqualTo(200);
        String runId = json(run1).get("id").toString();

        // merged params carry the profile rois + game traceability
        String paramsJson = jdbc.queryForObject(
                "SELECT params_json FROM analysis_runs WHERE id=?", String.class, runId);
        assertThat(paramsJson).contains("gameId").contains("gameGenre").contains("rois");
        Map<String, Object> params = com.highlighthub.common.Utils.fromJson(paramsJson, Map.class);
        assertThat(params.get("gameGenre")).isEqualTo("fps");

        // same profile again: finished runs reuse; but this one is still QUEUED, so a
        // second identical request must create a NEW run (no reuse of unfinished runs)
        var run2 = c.post("/api/media/m-game/analyses",
                Map.of("adapterVersionId", "av-g", "gameId", profile.get("id")));
        assertThat(json(run2).get("id")).isNotEqualTo(runId);
    }
}
