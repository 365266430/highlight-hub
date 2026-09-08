package com.highlighthub.upload;

import com.highlighthub.common.BusinessException;
import com.highlighthub.common.ErrorCodes;
import com.highlighthub.common.Utils;
import com.highlighthub.media.MediaEntity;
import com.highlighthub.media.MediaMapper;
import com.highlighthub.storage.LocalStorageService;
import com.highlighthub.storage.StorageService;
import com.highlighthub.task.TaskService;
import com.highlighthub.user.UserMapper;
import com.highlighthub.user.UserEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@Service
public class UploadService {
    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final UploadSessionMapper sessionMapper;
    private final UploadChunkMapper chunkMapper;
    private final MediaMapper mediaMapper;
    private final UserMapper userMapper;
    private final LocalStorageService storage;
    private final TaskService taskService;

    @Value("${highlight-hub.upload.max-file-size-bytes}")
    private long maxFileSizeBytes;

    @Value("${highlight-hub.upload.min-chunk-size-bytes}")
    private long minChunkSizeBytes;

    @Value("${highlight-hub.upload.max-chunk-size-bytes}")
    private long maxChunkSizeBytes;

    @Value("${highlight-hub.upload.max-chunk-count}")
    private int maxChunkCount;

    @Value("${highlight-hub.upload.session-ttl-minutes}")
    private int sessionTtlMinutes;

    public UploadService(UploadSessionMapper sessionMapper, UploadChunkMapper chunkMapper,
                         MediaMapper mediaMapper, UserMapper userMapper,
                         LocalStorageService storage, TaskService taskService,
                         org.springframework.transaction.PlatformTransactionManager txManager) {
        this.sessionMapper = sessionMapper;
        this.chunkMapper = chunkMapper;
        this.mediaMapper = mediaMapper;
        this.userMapper = userMapper;
        this.storage = storage;
        this.taskService = taskService;
        this.mergeTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    private final org.springframework.transaction.support.TransactionTemplate mergeTx;

    public record CreatedSession(String uploadId, long chunkSize, int expectedChunkCount,
                                 List<Integer> receivedChunks, String expiresAt) {}

    @Transactional
    public CreatedSession createSession(Long userId, String originalFilename, long declaredSize) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) throw BusinessException.notFound("user not found");
        if (originalFilename == null) originalFilename = "untitled";
        // filename is display-only; never used in path construction. strip path-looking junk.
        String display = originalFilename.replace('\\', '_').replace('/', '_')
                .replace("..", "_").replaceAll("[\\r\\n\\t]", " ");
        if (display.length() > 250) display = display.substring(0, 250);
        if (declaredSize <= 0) throw BusinessException.badRequest("declaredSize must be positive");
        if (declaredSize > maxFileSizeBytes) {
            throw new BusinessException(ErrorCodes.PAYLOAD_TOO_LARGE, 413,
                    "file exceeds the configured max size of " + maxFileSizeBytes + " bytes");
        }
        long chunkSize = chooseChunkSize(declaredSize);
        int chunkCount = (int) Math.max(1, (declaredSize + chunkSize - 1) / chunkSize);
        if (chunkCount > maxChunkCount) {
            throw BusinessException.badRequest("too many chunks; increase chunk size or reduce file size");
        }
        // atomic quota reservation: rejects when the remaining quota cannot cover the file
        int reserved = userMapper.reserveQuota(userId, declaredSize);
        if (reserved == 0) {
            throw new BusinessException(ErrorCodes.QUOTA_EXCEEDED, 429,
                    "storage quota exceeded; cannot reserve " + declaredSize + " bytes");
        }
        UploadSessionEntity session = new UploadSessionEntity();
        session.setId(Utils.newId());
        session.setUserId(userId);
        session.setOriginalFilename(display);
        session.setDeclaredSize(declaredSize);
        session.setChunkSize(chunkSize);
        session.setExpectedChunkCount(chunkCount);
        session.setStatus("UPLOADING");
        session.setReservedBytes(declaredSize);
        session.setReceivedBytes(0L);
        session.setExpiresAt(Utils.utcPlusMinutes(sessionTtlMinutes));
        session.setCreatedAt(Utils.utcNow());
        session.setUpdatedAt(Utils.utcNow());
        sessionMapper.insert(session);
        log.info("upload session {} created user {} declared {} bytes in {} chunks",
                session.getId(), userId, declaredSize, chunkCount);
        return new CreatedSession(session.getId(), chunkSize, chunkCount, List.of(), session.getExpiresAt().toString());
    }

    private long chooseChunkSize(long declaredSize) {
        // target: at most maxChunkCount chunks, within [min, max] chunk size
        long size = 8L * 1024 * 1024;
        while (size < maxChunkSizeBytes && (declaredSize + size - 1) / size > maxChunkCount) {
            size *= 2;
        }
        return Math.min(size, maxChunkSizeBytes);
    }

    public UploadSessionEntity requireOwnedSession(String uploadId, Long userId) {
        UploadSessionEntity s = sessionMapper.findById(uploadId);
        if (s == null) throw BusinessException.notFound("upload session not found");
        if (!s.getUserId().equals(userId)) {
            throw BusinessException.notFound("upload session not found"); // no existence leak
        }
        return s;
    }

    public record SessionView(String uploadId, String status, long declaredSize, long chunkSize,
                              int expectedChunkCount, List<Integer> receivedChunks,
                              long receivedBytes, String mediaId, String expiresAt,
                              String originalFilename) {}

    public SessionView view(UploadSessionEntity s) {
        List<Integer> received = chunkMapper.receivedIndexes(s.getId());
        return new SessionView(s.getId(), s.getStatus(), s.getDeclaredSize(), s.getChunkSize(),
                s.getExpectedChunkCount(), received, chunkMapper.totalReceivedBytes(s.getId()),
                s.getMediaId(), s.getExpiresAt().toString(), s.getOriginalFilename());
    }

    /** upload one chunk; same content returns accepted, different content under the same index is a conflict */
    public Map<String, Object> putChunk(Long userId, String uploadId, int chunkIndex, InputStream body) {
        UploadSessionEntity s = requireOwnedSession(uploadId, userId);
        if (!"UPLOADING".equals(s.getStatus())) {
            throw BusinessException.conflict(ErrorCodes.CONFLICT,
                    "session is " + s.getStatus() + ", chunks can no longer be uploaded");
        }
        if (s.getExpiresAt().isBefore(Utils.utcNow())) {
            throw new BusinessException(ErrorCodes.UPLOAD_SESSION_EXPIRED, 410, "upload session expired");
        }
        if (chunkIndex < 0 || chunkIndex >= s.getExpectedChunkCount()) {
            throw BusinessException.badRequest("chunkIndex out of range 0.." + (s.getExpectedChunkCount() - 1));
        }
        long chunkMax = s.getChunkSize();
        if (chunkIndex == s.getExpectedChunkCount() - 1) {
            long tail = s.getDeclaredSize() - chunkIndex * chunkMax;
            chunkMax = tail; // last chunk may be smaller but never larger
        }
        // stream to temp file computing sha256; hard cap the accepted bytes
        String tmpKey = storage.normalizeKey(StorageService.AssetType.TEMPORARY,
                "upload/" + s.getId() + "/chunk-" + chunkIndex + ".part");
        MessageDigest digest;
        long written;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = body; DigestInputStream din = new DigestInputStream(in, digest)) {
            written = storage.putLimited(tmpKey, din, chunkMax);
            if (written == -1) {
                storage.delete(tmpKey);
                throw new BusinessException(ErrorCodes.PAYLOAD_TOO_LARGE, 413,
                        "chunk larger than negotiated chunk size");
            }
        } catch (IOException e) {
            storage.delete(tmpKey);
            throw new BusinessException("STORAGE_WRITE_FAILED", 500, "failed to store chunk: " + e.getMessage());
        }
        if (written == 0) {
            storage.delete(tmpKey);
            throw BusinessException.badRequest("empty chunk body");
        }
        String checksum = Utils.hex(digest.digest());

        UploadChunkEntity existing = chunkMapper.findChunk(uploadId, chunkIndex);
        if (existing != null) {
            if (existing.getChecksum().equals(checksum) && existing.getActualSize() == written) {
                storage.delete(tmpKey); // duplicate of identical content
                return Map.of("index", chunkIndex, "received", true, "duplicate", true);
            }
            storage.delete(tmpKey);
            throw BusinessException.conflict(ErrorCodes.CHUNK_CONFLICT,
                    "chunk " + chunkIndex + " was already uploaded with different content");
        }
        String finalKey = storage.normalizeKey(StorageService.AssetType.TEMPORARY,
                "upload/" + s.getId() + "/chunk-" + chunkIndex);
        storage.move(tmpKey, finalKey);
        UploadChunkEntity chunk = new UploadChunkEntity();
        chunk.setUploadId(uploadId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setActualSize(written);
        chunk.setChecksum(checksum);
        chunk.setStorageKey(finalKey);
        chunk.setCreatedAt(Utils.utcNow());
        try {
            chunkMapper.insert(chunk);
            sessionMapper.updateReceivedBytes(uploadId);
        } catch (DuplicateKeyException e) {
            // raced with an identical upload: treat as duplicate if content matches
            UploadChunkEntity winner = chunkMapper.findChunk(uploadId, chunkIndex);
            if (winner != null && winner.getChecksum().equals(checksum)) {
                storage.delete(finalKey);
                return Map.of("index", chunkIndex, "received", true, "duplicate", true);
            }
            storage.delete(finalKey);
            throw BusinessException.conflict(ErrorCodes.CHUNK_CONFLICT,
                    "chunk " + chunkIndex + " concurrently uploaded with different content");
        }
        return Map.of("index", chunkIndex, "received", true, "duplicate", false);
    }

    /**
     * Idempotent completion. Exactly one call performs the merge; concurrent
     * callers get 409 while it runs, or the same media once completed.
     */
    public Map<String, Object> complete(Long userId, String uploadId) {
        UploadSessionEntity s = requireOwnedSession(uploadId, userId);
        if ("COMPLETED".equals(s.getStatus())) {
            return Map.of("mediaId", s.getMediaId(), "alreadyCompleted", true);
        }
        if ("COMPLETING".equals(s.getStatus())) {
            throw BusinessException.conflict(ErrorCodes.CONFLICT,
                    "completion already in progress; poll the session for the result");
        }
        if (!"UPLOADING".equals(s.getStatus())) {
            throw BusinessException.conflict(ErrorCodes.CONFLICT, "session is " + s.getStatus());
        }
        List<UploadChunkEntity> chunks = chunkMapper.listChunks(uploadId);
        if (chunks.size() != s.getExpectedChunkCount()) {
            throw BusinessException.conflict(ErrorCodes.CHUNKS_INCOMPLETE,
                    "missing chunks: expected " + s.getExpectedChunkCount() + ", received " + chunks.size());
        }
        long total = chunks.stream().mapToLong(UploadChunkEntity::getActualSize).sum();
        if (total != s.getDeclaredSize()) {
            throw BusinessException.badRequest("total received size " + total +
                    " does not match declared size " + s.getDeclaredSize());
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).getChunkIndex() != i) {
                throw BusinessException.conflict(ErrorCodes.CHUNKS_INCOMPLETE, "chunk index " + i + " missing");
            }
        }
        if (sessionMapper.tryStartCompleting(uploadId) == 0) {
            throw BusinessException.conflict(ErrorCodes.CONFLICT,
                    "completion already in progress; poll the session for the result");
        }
        try {
            String mediaId = mergeTx.execute(tx -> merge(userId, s, chunks));
            sessionMapper.finishComplete(uploadId, "COMPLETED", mediaId);
            return Map.of("mediaId", mediaId, "alreadyCompleted", false);
        } catch (RuntimeException e) {
            // allow a later retry of complete; chunks stay on disk
            sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UploadSessionEntity>()
                    .eq("id", uploadId).eq("status", "COMPLETING")
                    .set("status", "UPLOADING").set("updated_at", Utils.utcNow()));
            throw e;
        }
    }

    String merge(Long userId, UploadSessionEntity s, List<UploadChunkEntity> chunks) {
        String mediaId = Utils.newId();
        String finalKey = storage.normalizeKey(StorageService.AssetType.ORIGINAL, mediaId + "/source");
        // stream-merge into a temp object, never holding the whole video in memory
        String mergeTmp = storage.normalizeKey(StorageService.AssetType.TEMPORARY,
                "upload/" + s.getId() + "/merged");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        long totalWritten;
        try (OutputStream out = storage.openForWrite(mergeTmp)) {
            byte[] buf = new byte[256 * 1024];
            for (UploadChunkEntity chunk : chunks) {
                try (InputStream in = storage.open(chunk.getStorageKey());
                     DigestInputStream din = new DigestInputStream(in, digest)) {
                    long chunkBytes = chunk.getActualSize();
                    while (chunkBytes > 0) {
                        int n = din.read(buf, 0, (int) Math.min(buf.length, chunkBytes));
                        if (n < 0) throw new IOException("chunk file truncated: " + chunk.getStorageKey());
                        out.write(buf, 0, n);
                        chunkBytes -= n;
                    }
                } catch (IOException e) {
                    throw new BusinessException("STORAGE_WRITE_FAILED", 500, "merge failed: " + e.getMessage());
                }
            }
            totalWritten = storage.commitWritten(mergeTmp);
        } catch (IOException e) {
            throw new BusinessException("STORAGE_WRITE_FAILED", 500, "merge failed: " + e.getMessage());
        }
        if (totalWritten != s.getDeclaredSize()) {
            storage.delete(mergeTmp);
            throw new BusinessException(ErrorCodes.CONFLICT, 409,
                    "merged size " + totalWritten + " != declared " + s.getDeclaredSize());
        }
        String contentHash = Utils.hex(digest.digest());
        storage.move(mergeTmp, finalKey);

        // same-user content dedup: reuse the active media record, do not reveal other users' uploads
        MediaEntity dup = mediaMapper.findActiveByOwnerAndHash(userId, contentHash);
        if (dup != null) {
            storage.delete(finalKey);
            releaseReservation(s);
            log.info("upload {} deduped into existing media {}", s.getId(), dup.getId());
            return dup.getId();
        }

        MediaEntity media = new MediaEntity();
        media.setId(mediaId);
        media.setOwnerId(userId);
        media.setOriginalFilename(s.getOriginalFilename());
        media.setContentHash(contentHash);
        media.setFileSize(totalWritten);
        media.setStorageKey(finalKey);
        media.setStatus("PROBING");
        media.setRotation(0);
        media.setAudioStreamCount(0);
        media.setCreatedAt(Utils.utcNow());
        media.setUpdatedAt(Utils.utcNow());
        mediaMapper.insert(media);
        releaseReservation(s);
        // the reservation was based on the client-declared size; charge the verified bytes
        int charged = userMapper.reserveQuota(userId, totalWritten);
        if (charged == 0) {
            throw new BusinessException(ErrorCodes.QUOTA_EXCEEDED, 429,
                    "verified file size " + totalWritten + " bytes exceeds the remaining quota");
        }
        taskService.create("PROBE", userId, mediaId, null, Map.of("mediaId", mediaId));
        log.info("media {} created from upload {}", mediaId, s.getId());
        return mediaId;
    }

    private void releaseReservation(UploadSessionEntity s) {
        userMapper.releaseQuota(s.getUserId(), s.getReservedBytes());
    }

    @Transactional
    public void cancel(Long userId, String uploadId) {
        UploadSessionEntity s = requireOwnedSession(uploadId, userId);
        if ("COMPLETED".equals(s.getStatus())) {
            throw BusinessException.conflict(ErrorCodes.CONFLICT, "session already completed; delete the media instead");
        }
        List<UploadChunkEntity> chunks = chunkMapper.listChunks(uploadId);
        for (UploadChunkEntity c : chunks) {
            storage.delete(c.getStorageKey());
        }
        chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UploadChunkEntity>()
                .eq("upload_id", uploadId));
        storage.deletePrefix(storage.normalizeKey(StorageService.AssetType.TEMPORARY, "upload/" + uploadId));
        sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UploadSessionEntity>()
                .eq("id", uploadId)
                .set("status", "CANCELLED").set("updated_at", Utils.utcNow()));
        releaseReservation(s);
    }

    /** TTL cleanup: expired sessions release quota and temp objects. */
    @Transactional
    public void sweepExpired() {
        List<UploadSessionEntity> expired = sessionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UploadSessionEntity>()
                        .eq("status", "UPLOADING").lt("expires_at", Utils.utcNow()).last("LIMIT 100"));
        for (UploadSessionEntity s : expired) {
            List<UploadChunkEntity> chunks = chunkMapper.listChunks(s.getId());
            boolean allDeleted = true;
            for (UploadChunkEntity c : chunks) {
                try {
                    storage.delete(c.getStorageKey());
                } catch (RuntimeException e) {
                    log.warn("cleanup of chunk {} failed: {}", c.getStorageKey(), e.getMessage());
                    allDeleted = false;
                }
            }
            if (!allDeleted) continue; // retry on a later sweep; keep the reservation
            chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UploadChunkEntity>()
                    .eq("upload_id", s.getId()));
            storage.deletePrefix(storage.normalizeKey(StorageService.AssetType.TEMPORARY, "upload/" + s.getId()));
            sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UploadSessionEntity>()
                    .eq("id", s.getId()).eq("status", "UPLOADING")
                    .set("status", "EXPIRED").set("updated_at", Utils.utcNow()));
            releaseReservation(s);
            log.info("expired upload session {} cleaned up", s.getId());
        }
        for (String id : sessionMapper.findStaleCompletingIds(10)) {
            sessionMapper.resetStaleCompleting(10);
            log.warn("upload session {} stuck in COMPLETING was reset to UPLOADING", id);
        }
    }
}
