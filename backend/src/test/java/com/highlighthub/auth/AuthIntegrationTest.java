package com.highlighthub.auth;

import com.highlighthub.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIntegrationTest extends AbstractIntegrationTest {

    @Test
    void registerLoginMeFlow() {
        Client c = client();
        c.fetchCsrf();
        ResponseEntity<String> reg = c.post("/api/auth/register",
                Map.of("username", "alice_01", "password", "password123", "displayName", "Alice"));
        assertThat(reg.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> user = json(reg);
        assertThat(user.get("username")).isEqualTo("alice_01");
        assertThat(user.get("role")).isEqualTo("USER");
        assertThat(user.containsKey("passwordHash")).isFalse();

        // second registration with the same username conflicts
        ResponseEntity<String> dup = c.post("/api/auth/register",
                Map.of("username", "alice_01", "password", "password456"));
        assertThat(dup.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> login = c.post("/api/auth/login",
                Map.of("username", "alice_01", "password", "password123"));
        assertThat(login.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> me = c.get("/api/me");
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(json(me).get("username")).isEqualTo("alice_01");
    }

    @Test
    void meRequiresLogin() {
        ResponseEntity<String> me = client().get("/api/me");
        assertThat(me.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void wrongPasswordRejected() {
        Client c = client();
        c.register("bob_02", "password123");
        ResponseEntity<String> bad = c.post("/api/auth/login", Map.of("username", "bob_02", "password", "wrongpass1"));
        assertThat(bad.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void duplicateUsernameConflicts() {
        client().register("carol_3", "password123");
        Client c2 = client();
        c2.fetchCsrf();
        ResponseEntity<String> dup = c2.post("/api/auth/register",
                Map.of("username", "carol_3", "password", "password456"));
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void invalidUsernamesRejected() {
        Client c = client();
        c.fetchCsrf();
        assertThat(c.post("/api/auth/register", Map.of("username", "x", "password", "password123"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(c.post("/api/auth/register", Map.of("username", "bad name!", "password", "password123"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(c.post("/api/auth/register", Map.of("username", "okname1", "password", "short"))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void logoutInvalidatesSession() {
        Client c = client();
        c.registerAndLogin("dave_04", "password123");
        assertThat(c.get("/api/me").getStatusCode().value()).isEqualTo(200);
        c.post("/api/auth/logout", Map.of());
        assertThat(c.get("/api/me").getStatusCode().value()).isEqualTo(401);
    }
}
