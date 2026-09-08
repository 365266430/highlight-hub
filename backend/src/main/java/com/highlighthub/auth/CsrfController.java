package com.highlighthub.auth;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Hands the CSRF token to browser clients. Accessing the CsrfToken triggers the
 * repository to write the XSRF-TOKEN cookie; the client echoes that value back
 * in the X-XSRF-TOKEN header on state-changing requests.
 */
@RestController
public class CsrfController {

    @GetMapping("/api/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "header", token.getHeaderName());
    }
}
