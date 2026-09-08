package com.highlighthub.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Two responsibilities:
 * 1. attach a requestId to every request for log correlation;
 * 2. guard /internal/** endpoints with the shared worker token
 *    (attemptToken is per-task, never a substitute for service auth).
 */
@Component
public class RequestAndWorkerFilter extends OncePerRequestFilter {

    @Value("${highlight-hub.worker.token}")
    private String workerToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        try {
            String uri = request.getRequestURI();
            if (uri.startsWith("/internal/")) {
                String token = request.getHeader("X-Worker-Token");
                if (token == null || !constantTimeEquals(token, workerToken)) {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"invalid worker token\",\"requestId\":\"" + requestId + "\",\"details\":null}");
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
