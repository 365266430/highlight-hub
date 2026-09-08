package com.highlighthub.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public final class Utils {
    public static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Utils() {}

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String newToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    public static LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    public static LocalDateTime utcPlusSeconds(long seconds) {
        return LocalDateTime.ofInstant(Instant.now().plusSeconds(seconds), ZoneOffset.UTC);
    }

    public static LocalDateTime utcPlusMinutes(long minutes) {
        return LocalDateTime.ofInstant(Instant.now().plusSeconds(minutes * 60), ZoneOffset.UTC);
    }

    public static String sha256Hex(byte[] data) {
        return hex(doDigest(data));
    }

    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] doDigest(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return sb.toString();
    }

    public static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("json serialization failed", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("json deserialization failed", e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("json deserialization failed", e);
        }
    }

    public static String requestHash(Object requestBody) {
        return requestBody == null ? sha256Hex("") : sha256Hex(toJson(requestBody));
    }
}
