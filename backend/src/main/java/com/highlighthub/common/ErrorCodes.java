package com.highlighthub.common;

/**
 * Unified error codes returned as {code, message, requestId, details}.
 */
public final class ErrorCodes {
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String UPLOAD_SESSION_EXPIRED = "UPLOAD_SESSION_EXPIRED";
    public static final String CHUNK_CONFLICT = "CHUNK_CONFLICT";
    public static final String CHUNKS_INCOMPLETE = "CHUNKS_INCOMPLETE";
    public static final String INVALID_MEDIA = "INVALID_MEDIA";
    public static final String TASK_CONFLICT = "TASK_CONFLICT";
    public static final String INTERNAL = "INTERNAL";

    private ErrorCodes() {}
}
