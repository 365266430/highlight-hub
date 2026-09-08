package com.highlighthub.media;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("media")
public class MediaEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long ownerId;
    private String originalFilename;
    private String contentHash;
    private Long fileSize;
    private String storageKey;
    private String status;
    private Long durationMs;
    private Integer width;
    private Integer height;
    private String videoCodec;
    private String audioCodec;
    private BigDecimal frameRate;
    private Integer variableFrameRate;
    private Integer rotation;
    private Integer audioStreamCount;
    private String probeJson;
    private String thumbnailJson;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public String getVideoCodec() { return videoCodec; }
    public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }
    public String getAudioCodec() { return audioCodec; }
    public void setAudioCodec(String audioCodec) { this.audioCodec = audioCodec; }
    public BigDecimal getFrameRate() { return frameRate; }
    public void setFrameRate(BigDecimal frameRate) { this.frameRate = frameRate; }
    public Integer getVariableFrameRate() { return variableFrameRate; }
    public void setVariableFrameRate(Integer variableFrameRate) { this.variableFrameRate = variableFrameRate; }
    public Integer getRotation() { return rotation; }
    public void setRotation(Integer rotation) { this.rotation = rotation; }
    public Integer getAudioStreamCount() { return audioStreamCount; }
    public void setAudioStreamCount(Integer audioStreamCount) { this.audioStreamCount = audioStreamCount; }
    public String getProbeJson() { return probeJson; }
    public void setProbeJson(String probeJson) { this.probeJson = probeJson; }
    public String getThumbnailJson() { return thumbnailJson; }
    public void setThumbnailJson(String thumbnailJson) { this.thumbnailJson = thumbnailJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
