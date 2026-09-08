package com.highlighthub;

import com.highlighthub.common.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Integration tests run against a real local MySQL 8 database (Docker/Testcontainers
 * unavailable in this environment - documented in docs/test-report.md).
 * Each test starts from a truncated schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    private static final List<String> TABLES = List.of(
            "consumed_events", "outbox_events", "share_links", "idempotency_records", "task_attempts",
            "tasks", "render_jobs", "editing_project_revisions", "editing_projects",
            "highlight_candidates", "highlight_runs", "highlight_rule_versions", "event_revisions",
            "video_events", "analysis_runs", "adapter_versions", "adapter_definitions",
            "media_assets", "media", "upload_chunks", "upload_sessions", "users");

    @BeforeEach
    void cleanDatabase() {
        // java.net.http.HttpClient treats 401 with a body as a plain response
        // (HttpURLConnection would attempt an auth retry and throw mid-stream)
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : TABLES) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    public class Client {
        final HttpHeaders extraHeaders = new HttpHeaders();
        public final List<String> cookies = new ArrayList<>();
        public String csrfToken;

        void absorbCookies(HttpHeaders responseHeaders) {
            List<String> setCookies = responseHeaders.get(HttpHeaders.SET_COOKIE);
            if (setCookies == null) return;
            for (String cookie : setCookies) {
                String pair = cookie.split(";", 2)[0];
                String name = pair.substring(0, pair.indexOf('=')).trim();
                if (name.equals("XSRF-TOKEN")) {
                    // the raw cookie value is what the client must echo back (no decoding)
                    String token = pair.substring("XSRF-TOKEN=".length());
                    cookies.removeIf(c -> c.startsWith("XSRF-TOKEN="));
                    cookies.add("XSRF-TOKEN=" + token);
                    csrfToken = token;
                } else if (name.equals("HUBSESSION") || name.equals("SESSION")) {
                    cookies.removeIf(c -> c.startsWith(name + "="));
                    cookies.add(pair);
                }
            }
        }

        public void fetchCsrf() {
            ResponseEntity<String> resp = rest.getForEntity("/api/csrf", String.class);
            absorbCookies(resp.getHeaders());
            if (csrfToken == null) {
                // token also returned in the body as a fallback
                Map<String, Object> body = Utils.fromJson(resp.getBody(), Map.class);
                if (body != null && body.get("token") != null) {
                    csrfToken = (String) body.get("token");
                    cookies.removeIf(c -> c.startsWith("XSRF-TOKEN="));
                    cookies.add("XSRF-TOKEN=" + csrfToken);
                }
            }
        }

        public void register(String username, String password) {
            if (csrfToken == null) fetchCsrf();
            post("/api/auth/register", Map.of("username", username, "password", password, "displayName", username));
        }

        public void login(String username, String password) {
            if (csrfToken == null) fetchCsrf();
            post("/api/auth/login", Map.of("username", username, "password", password));
        }

        public void registerAndLogin(String username, String password) {
            register(username, password);
            login(username, password);
        }

        public ResponseEntity<String> post(String path, Object body) {
            return exchange(HttpMethod.POST, path, body);
        }

        public ResponseEntity<String> put(String path, Object body) {
            return exchange(HttpMethod.PUT, path, body);
        }

        public ResponseEntity<String> get(String path) {
            return exchange(HttpMethod.GET, path, null);
        }

        public ResponseEntity<String> delete(String path) {
            return exchange(HttpMethod.DELETE, path, null);
        }

        public ResponseEntity<String> exchange(HttpMethod method, String path, Object body) {
            HttpHeaders h = new HttpHeaders();
            h.putAll(extraHeaders);
            if (!cookies.isEmpty()) {
                h.add(HttpHeaders.COOKIE, String.join("; ", cookies));
            }
            if (csrfToken != null) {
                h.add("X-XSRF-TOKEN", csrfToken);
            }
            if (body != null) {
                if (body instanceof byte[] bytes) {
                    h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
                    h.setContentLength(bytes.length); // avoid chunked encoding on early server responses
                } else {
                    h.setContentType(MediaType.APPLICATION_JSON);
                }
            }
            return rest.execute(path, method, request -> {
                request.getHeaders().putAll(h);
                if (body != null) {
                    byte[] payload = body instanceof byte[] bytes ? bytes
                            : Utils.toJson(body).getBytes(StandardCharsets.UTF_8);
                    try (var out = request.getBody()) {
                        StreamUtils.copy(new ByteArrayInputStream(payload), out);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, response -> {
                absorbCookies(response.getHeaders());
                byte[] bytes = StreamUtils.copyToByteArray(response.getBody());
                return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                        .body(new String(bytes, StandardCharsets.UTF_8));
            });
        }

        public ResponseEntity<String> putRaw(String path, byte[] data) {
            return exchange(HttpMethod.PUT, path, data);
        }
    }

    protected Client client() {
        return new Client();
    }

    protected Map<String, Object> json(ResponseEntity<String> resp) {
        return Utils.fromJson(resp.getBody(), Map.class);
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> jsonList(ResponseEntity<String> resp) {
        return (List<Map<String, Object>>) Utils.fromJson(resp.getBody(), List.class);
    }
}
