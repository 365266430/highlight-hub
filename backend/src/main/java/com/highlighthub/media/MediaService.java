package com.highlighthub.media;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.highlighthub.auth.SecurityUtils;
import com.highlighthub.common.BusinessException;
import com.highlighthub.common.ErrorCodes;
import com.highlighthub.common.Utils;
import com.highlighthub.storage.LocalStorageService;
import com.highlighthub.user.UserMapper;
import com.highlighthub.storage.StorageService;
import com.highlighthub.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaMapper mediaMapper;
    private final MediaAssetMapper assetMapper;
    private final LocalStorageService storage;
    private final TaskService taskService;
    private final com.highlighthub.user.UserMapper userMapper;

    @Value("${highlight-hub.media.preview.max-height}")
    private int previewMaxHeight;

    @Value("${highlight-hub.media.preview.crf}")
    private int previewCrf;

    @Value("${highlight-hub.media.thumbnail.interval-seconds}")
    private int thumbnailIntervalSeconds;

    @Value("${highlight-hub.media.thumbnail.tile-columns}")
    private int tileColumns;

    @Value("${highlight-hub.media.thumbnail.tile-rows}")
    private int tileRows;

    @Value("${highlight-hub.media.thumbnail.frame-width}")
    private int frameWidth;

    @Value("${highlight-hub.ffmpeg.path}")
    private String ffmpegPath;

    @Value("${highlight-hub.ffmpeg.ffprobe-path}")
    private String ffprobePath;

    public MediaService(MediaMapper mediaMapper, MediaAssetMapper assetMapper,
                        LocalStorageService storage, TaskService taskService,
                        com.highlighthub.user.UserMapper userMapper) {
        this.mediaMapper = mediaMapper;
        this.assetMapper = assetMapper;
        this.storage = storage;
        this.taskService = taskService;
        this.userMapper = userMapper;
    }

    public MediaEntity requireOwnedMedia(String mediaId, Long userId) {
        MediaEntity m = mediaMapper.findById(mediaId);
        if (m == null) throw BusinessException.notFound("media not found");
        if (!m.getOwnerId().equals(userId)) {
            throw BusinessException.notFound("media not found"); // no existence leak across users
        }
        return m;
    }

    public MediaEntity requireMediaForTask(String mediaId) {
        MediaEntity m = mediaMapper.findById(mediaId);
        if (m == null) throw BusinessException.notFound("media not found");
        return m;
    }

    public Map<String, Object> view(MediaEntity m) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", m.getId());
        view.put("originalFilename", m.getOriginalFilename());
        view.put("status", m.getStatus());
        view.put("fileSize", m.getFileSize());
        view.put("durationMs", m.getDurationMs());
        view.put("width", m.getWidth());
        view.put("height", m.getHeight());
        view.put("videoCodec", m.getVideoCodec());
        view.put("audioCodec", m.getAudioCodec());
        view.put("frameRate", m.getFrameRate() == null ? null : m.getFrameRate().doubleValue());
        view.put("variableFrameRate", m.getVariableFrameRate() != null && m.getVariableFrameRate() == 1);
        view.put("rotation", m.getRotation());
        view.put("audioStreamCount", m.getAudioStreamCount());
        view.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
        if (m.getErrorMessage() != null) view.put("errorMessage", m.getErrorMessage());
        MediaAssetEntity preview = assetMapper.findLatestByMediaAndType(m.getId(), "PREVIEW");
        view.put("hasPreview", preview != null);
        MediaAssetEntity thumb = assetMapper.findLatestByMediaAndType(m.getId(), "THUMBNAIL");
        view.put("hasThumbnails", thumb != null);
        if (m.getThumbnailJson() != null) view.put("thumbnailIndex", Utils.fromJson(m.getThumbnailJson(), Map.class));
        return view;
    }

    public IPage<MediaEntity> pageForOwner(Long ownerId, int page, int size) {
        return mediaMapper.selectPage(new Page<>(page, Math.min(size, 100)),
                new QueryWrapper<MediaEntity>().eq("owner_id", ownerId)
                        .ne("status", "DELETED")
                        .orderByDesc("created_at"));
    }

    public void deleteMedia(Long userId, String mediaId) {
        MediaEntity m = requireOwnedMedia(mediaId, userId);
        // soft delete first; physical cleanup runs as a task
        mediaMapper.transition(mediaId, m.getStatus(), "DELETING");
        Map<String, Object> cleanupPayload = new LinkedHashMap<>();
        cleanupPayload.put("mediaId", mediaId);
        cleanupPayload.put("requestedBy", userId);
        taskService.create("CLEANUP", null, mediaId, null, cleanupPayload, 5);
        log.info("media {} marked DELETING by user {}", mediaId, userId);
    }

    public void applyProbeResult(String mediaId, Map<String, Object> result) {
        MediaEntity m = mediaMapper.findById(mediaId);
        if (m == null) {
            log.warn("probe result for unknown media {} ignored", mediaId);
            return;
        }
        if (!"PROBING".equals(m.getStatus())) {
            log.warn("probe result for media {} in status {} ignored", mediaId, m.getStatus());
            return;
        }
        m.setDurationMs(((Number) result.get("durationMs")).longValue());
        m.setWidth((Integer) result.get("width"));
        m.setHeight((Integer) result.get("height"));
        m.setVideoCodec((String) result.get("videoCodec"));
        m.setAudioCodec((String) result.get("audioCodec"));
        if (result.get("frameRate") != null) {
            m.setFrameRate(new java.math.BigDecimal(String.valueOf(result.get("frameRate"))));
        }
        m.setVariableFrameRate(Boolean.TRUE.equals(result.get("variableFrameRate")) ? 1 : 0);
        m.setRotation(result.get("rotation") == null ? 0 : ((Number) result.get("rotation")).intValue());
        m.setAudioStreamCount(result.get("audioStreamCount") == null ? 0
                : ((Number) result.get("audioStreamCount")).intValue());
        m.setProbeJson(Utils.toJson(result));
        m.setStatus("READY");
        m.setUpdatedAt(Utils.utcNow());
        mediaMapper.updateById(m);

        // pipeline continues after a successful probe
        Map<String, Object> previewPayload = new LinkedHashMap<>(Map.of("mediaId", mediaId));
        previewPayload.put("preview", previewParams());
        taskService.create("PREVIEW", m.getOwnerId(), mediaId, null, previewPayload);
        Map<String, Object> thumbPayload = new LinkedHashMap<>(Map.of("mediaId", mediaId));
        thumbPayload.put("thumbnail", thumbnailParams());
        taskService.create("THUMBNAIL", m.getOwnerId(), mediaId, null, thumbPayload);
        log.info("media {} READY: {}x{} {} ms", mediaId, m.getWidth(), m.getHeight(), m.getDurationMs());
    }

    /** invoked after the physical cleanup task succeeded: close out the deletion */
    public void finalizeDeletion(String mediaId, Long requestedBy) {
        MediaEntity m = mediaMapper.findById(mediaId);
        if (m == null) {
            log.warn("cleanup for unknown media {} ignored", mediaId);
            return;
        }
        if (!"DELETING".equals(m.getStatus())) {
            log.warn("cleanup for media {} in status {} ignored", mediaId, m.getStatus());
            return;
        }
        m.setStatus("DELETED");
        m.setUpdatedAt(Utils.utcNow());
        mediaMapper.updateById(m);
        // assets become DELETED records; original/preview/thumbnail files were removed by the worker
        List<MediaAssetEntity> assets = assetMapper.selectList(
                new QueryWrapper<MediaAssetEntity>().eq("media_id", mediaId));
        for (MediaAssetEntity a : assets) {
            a.setStatus("DELETED");
            assetMapper.updateById(a);
        }
        if (requestedBy != null) {
            userMapper.releaseQuota(m.getOwnerId(), m.getFileSize());
        }
        log.info("media {} fully deleted; released {} bytes of quota", mediaId, m.getFileSize());
    }

    public void applyPreviewResult(String mediaId, Map<String, Object> result) {
        MediaEntity m = mediaMapper.findById(mediaId);
        if (m == null) return;
        String key = (String) result.get("storageKey");
        long size = ((Number) result.get("size")).longValue();
        upsertAsset(m, "PREVIEW", key, size, null);
    }

    public void applyThumbnailResult(String mediaId, Map<String, Object> result) {
        MediaEntity m = mediaMapper.findById(mediaId);
        if (m == null) return;
        String key = (String) result.get("storageKey");
        long size = ((Number) result.get("size")).longValue();
        upsertAsset(m, "THUMBNAIL", key, size, null);
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("storageKey", key);
        index.put("intervalSeconds", result.get("intervalSeconds"));
        index.put("columns", result.get("columns"));
        index.put("rows", result.get("rows"));
        index.put("frameWidth", result.get("frameWidth"));
        index.put("frameHeight", result.get("frameHeight"));
        index.put("count", result.get("count"));
        index.put("spriteVersion", result.get("spriteVersion"));
        m.setThumbnailJson(Utils.toJson(index));
        m.setUpdatedAt(Utils.utcNow());
        mediaMapper.updateById(m);
    }

    public void markMediaFailed(String mediaId, String errorCode, String message) {
        MediaEntity m = mediaMapper.findById(mediaId);
        if (m == null) return;
        m.setStatus("FAILED");
        m.setErrorMessage(message == null ? errorCode : errorCode + ": " + message);
        m.setUpdatedAt(Utils.utcNow());
        mediaMapper.updateById(m);
        log.warn("media {} marked FAILED ({}: {})", mediaId, errorCode, message);
    }

    private void upsertAsset(MediaEntity m, String type, String key, long size, String checksum) {
        // deterministic regeneration: mark older assets deleted, insert the fresh one
        List<MediaAssetEntity> existing = assetMapper.selectList(
                new QueryWrapper<MediaAssetEntity>().eq("media_id", m.getId()).eq("type", type));
        for (MediaAssetEntity a : existing) {
            a.setStatus("DELETED");
            assetMapper.updateById(a);
        }
        MediaAssetEntity asset = new MediaAssetEntity();
        asset.setOwnerId(m.getOwnerId());
        asset.setMediaId(m.getId());
        asset.setType(type);
        asset.setStorageKey(key);
        asset.setSize(size);
        asset.setChecksum(checksum);
        asset.setStatus("ACTIVE");
        asset.setCreatedAt(Utils.utcNow());
        assetMapper.insert(asset);
    }

    // preview generation parameters shared with the worker payload
    public Map<String, Object> previewParams() {
        return Map.of("maxHeight", previewMaxHeight, "crf", previewCrf);
    }

    public Map<String, Object> thumbnailParams() {
        return Map.of("intervalSeconds", thumbnailIntervalSeconds, "columns", tileColumns,
                "rows", tileRows, "frameWidth", frameWidth);
    }

    public String ffprobePath() { return ffprobePath; }
    public String ffmpegPath() { return ffmpegPath; }

    public record RangeResult(String storageKey, long size, InputStream stream, long start, long length) {}

    /** authorize + open a byte range of an owned media asset (original or preview) */
    public RangeResult openRange(Long userId, String mediaId, String assetType, String rangeHeader) {
        MediaEntity m = requireOwnedMedia(mediaId, userId);
        MediaAssetEntity asset = assetMapper.findLatestByMediaAndType(mediaId, assetType);
        String key;
        long size;
        if ("ORIGINAL".equals(assetType)) {
            key = m.getStorageKey();
            size = m.getFileSize();
        } else if (asset != null) {
            key = asset.getStorageKey();
            size = asset.getSize();
        } else {
            throw BusinessException.notFound(assetType.toLowerCase() + " not available yet");
        }
        long start = 0;
        long length = size;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] parts = rangeHeader.substring(6).split("-", 2);
            try {
                start = Long.parseLong(parts[0].trim());
                if (!parts[1].isBlank()) {
                    long endInclusive = Long.parseLong(parts[1].trim());
                    length = endInclusive - start + 1;
                } else {
                    length = size - start;
                }
            } catch (NumberFormatException e) {
                throw BusinessException.badRequest("malformed Range header");
            }
            if (start < 0 || start >= size || length <= 0) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, 416, "requested range not satisfiable");
            }
            length = Math.min(length, size - start);
        }
        return new RangeResult(key, size, storage.openRange(key, start, length), start, length);
    }

    public void assertStatusReady(MediaEntity m) {
        if (!"READY".equals(m.getStatus())) {
            throw new BusinessException(ErrorCodes.SERVICE_UNAVAILABLE, 503,
                    "media is " + m.getStatus() + "; try again when processing finishes");
        }
    }

    /** open an arbitrary storage key owned by the user (render outputs, etc.) */
    public RangeResult openRangeByKey(Long ownerId, String storageKey, long size, String rangeHeader) {
        long start = 0;
        long length = size;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] parts = rangeHeader.substring(6).split("-", 2);
            try {
                start = Long.parseLong(parts[0].trim());
                if (!parts[1].isBlank()) {
                    long endInclusive = Long.parseLong(parts[1].trim());
                    length = endInclusive - start + 1;
                } else {
                    length = size - start;
                }
            } catch (NumberFormatException e) {
                throw BusinessException.badRequest("malformed Range header");
            }
            if (start < 0 || start >= size || length <= 0) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, 416, "requested range not satisfiable");
            }
            length = Math.min(length, size - start);
        }
        return new RangeResult(storageKey, size, storage.openRange(storageKey, start, length), start, length);
    }

    public InputStream openByKey(String storageKey) {
        return storage.open(storageKey);
    }
}
