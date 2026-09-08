package com.highlighthub.analysis;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("video_events")
public class VideoEventEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String analysisRunId;
    private String mediaId;
    private Long ownerId;
    private String type;
    private Long startMs;
    private Long endMs;
    private String actor;
    private String target;
    private BigDecimal confidence;
    private String source;
    private String status;
    private String evidenceAssetId;
    private String attributes;
    private String adapterVersionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAnalysisRunId() { return analysisRunId; }
    public void setAnalysisRunId(String analysisRunId) { this.analysisRunId = analysisRunId; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getStartMs() { return startMs; }
    public void setStartMs(Long startMs) { this.startMs = startMs; }
    public Long getEndMs() { return endMs; }
    public void setEndMs(Long endMs) { this.endMs = endMs; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEvidenceAssetId() { return evidenceAssetId; }
    public void setEvidenceAssetId(String evidenceAssetId) { this.evidenceAssetId = evidenceAssetId; }
    public String getAttributes() { return attributes; }
    public void setAttributes(String attributes) { this.attributes = attributes; }
    public String getAdapterVersionId() { return adapterVersionId; }
    public void setAdapterVersionId(String adapterVersionId) { this.adapterVersionId = adapterVersionId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
