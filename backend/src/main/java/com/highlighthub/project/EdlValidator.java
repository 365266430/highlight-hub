package com.highlighthub.project;

import com.highlighthub.common.BusinessException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side authority over the Edit Decision List (schema v1).
 * Clients never pass raw ffmpeg parameters; only this whitelist survives.
 */
public final class EdlValidator {

    public record Segment(String id, long sourceInMs, long sourceOutMs, String caption, double sourceVolume) {}
    public record Output(String aspectMode, int width, int height, int fps) {}
    public record Edl(int schemaVersion, String sourceMediaId, List<Segment> segments, Output output) {}

    private EdlValidator() {}

    public static Edl parseAndValidate(String json, long mediaDurationMs, int maxSegments,
                                       long maxTotalOutputMs, int maxOutputHeight, int maxCaptionChars) {
        Edl edl = com.highlighthub.common.Utils.fromJson(json, Edl.class);
        validate(edl, mediaDurationMs, maxSegments, maxTotalOutputMs, maxOutputHeight, maxCaptionChars);
        return edl;
    }

    public static void validate(Edl edl, long mediaDurationMs, int maxSegments,
                                long maxTotalOutputMs, int maxOutputHeight, int maxCaptionChars) {
        if (edl == null) throw BusinessException.badRequest("edit decision list is required");
        if (edl.schemaVersion() != 1) throw BusinessException.badRequest("unsupported schemaVersion");
        if (edl.sourceMediaId() == null || edl.sourceMediaId().isBlank()) {
            throw BusinessException.badRequest("sourceMediaId is required");
        }
        List<Segment> segments = edl.segments();
        if (segments == null || segments.isEmpty()) throw BusinessException.badRequest("at least one segment is required");
        if (segments.size() > maxSegments) {
            throw BusinessException.badRequest("too many segments (max " + maxSegments + ")");
        }
        Set<String> ids = new HashSet<>();
        long totalOut = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            String id = s.id() == null || s.id().isBlank() ? "segment-" + (i + 1) : s.id();
            if (!ids.add(id)) throw BusinessException.badRequest("duplicate segment id: " + id);
            if (s.sourceInMs() < 0) throw BusinessException.badRequest("segment " + id + ": sourceInMs must be >= 0");
            if (s.sourceOutMs() <= s.sourceInMs()) {
                throw BusinessException.badRequest("segment " + id + ": sourceOutMs must be greater than sourceInMs");
            }
            if (mediaDurationMs > 0 && s.sourceOutMs() > mediaDurationMs) {
                throw BusinessException.badRequest("segment " + id + ": sourceOutMs exceeds media duration " + mediaDurationMs);
            }
            if (s.caption() != null && s.caption().length() > maxCaptionChars) {
                throw BusinessException.badRequest("segment " + id + ": caption too long (max " + maxCaptionChars + ")");
            }
            if (s.sourceVolume() < 0.0 || s.sourceVolume() > 1.5) {
                throw BusinessException.badRequest("segment " + id + ": sourceVolume must be within [0, 1.5]");
            }
            totalOut += s.sourceOutMs() - s.sourceInMs();
        }
        if (maxTotalOutputMs > 0 && totalOut > maxTotalOutputMs) {
            throw BusinessException.badRequest("total output duration " + totalOut + " ms exceeds the limit " + maxTotalOutputMs + " ms");
        }
        Output out = edl.output();
        if (out == null) throw BusinessException.badRequest("output settings are required");
        if (!"SOURCE".equals(out.aspectMode())) {
            throw BusinessException.badRequest("aspectMode must be SOURCE in this version");
        }
        if (out.width() <= 0 || out.width() > 3840 || (out.width() % 2) != 0) {
            throw BusinessException.badRequest("output width must be an even number in (0, 3840]");
        }
        if (out.height() <= 0 || out.height() > maxOutputHeight || (out.height() % 2) != 0) {
            throw BusinessException.badRequest("output height must be an even number in (0, " + maxOutputHeight + "]");
        }
        if (!Set.of(24, 25, 30, 50, 60).contains(out.fps())) {
            throw BusinessException.badRequest("output fps must be one of 24/25/30/50/60");
        }
    }

    /** serialize back to a normalized JSON string stored in the revision */
    public static String normalize(Edl edl) {
        return com.highlighthub.common.Utils.toJson(edl);
    }

    public static String resultTextSummary(Map<String, Object> result) {
        return result == null ? "" : String.valueOf(result.get("storageKey"));
    }
}
