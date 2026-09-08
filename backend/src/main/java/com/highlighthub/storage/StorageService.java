package com.highlighthub.storage;

import java.io.InputStream;

/**
 * Storage abstraction. Physical paths never leave the server; callers
 * use logical storage keys resolved against the configured root.
 */
public interface StorageService {

    enum AssetType {
        ORIGINAL("original"),
        PREVIEW("preview"),
        THUMBNAIL("thumbnail"),
        EVIDENCE("evidence"),
        RENDER_OUTPUT("render"),
        TEMPORARY("tmp");

        private final String dir;

        AssetType(String dir) { this.dir = dir; }

        public String dir() { return dir; }
    }

    /** Store bytes from a stream under a logical key. Returns bytes written. */
    long put(String key, InputStream in, long expectedSizeIfExists);

    /** Open a stream for reading the whole object. Caller closes. */
    InputStream open(String key);

    /** Read a byte range. Caller closes. */
    InputStream openRange(String key, long start, long length);

    boolean exists(String key);

    long size(String key);

    void delete(String key);

    /** Resolve a logical key to an absolute local filesystem path (internal use only, e.g. ffmpeg input). */
    String resolveLocalPath(String key);

    String normalizeKey(AssetType type, String relativeName);
}
