package com.highlighthub.upload;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("upload_sessions")
public class UploadSessionEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private String originalFilename;
    private Long declaredSize;
    private Long chunkSize;
    private Integer expectedChunkCount;
    private String status;
    private Long reservedBytes;
    private Long receivedBytes;
    private String mediaId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public Long getDeclaredSize() { return declaredSize; }
    public void setDeclaredSize(Long declaredSize) { this.declaredSize = declaredSize; }
    public Long getChunkSize() { return chunkSize; }
    public void setChunkSize(Long chunkSize) { this.chunkSize = chunkSize; }
    public Integer getExpectedChunkCount() { return expectedChunkCount; }
    public void setExpectedChunkCount(Integer expectedChunkCount) { this.expectedChunkCount = expectedChunkCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getReservedBytes() { return reservedBytes; }
    public void setReservedBytes(Long reservedBytes) { this.reservedBytes = reservedBytes; }
    public Long getReceivedBytes() { return receivedBytes; }
    public void setReceivedBytes(Long receivedBytes) { this.receivedBytes = receivedBytes; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
