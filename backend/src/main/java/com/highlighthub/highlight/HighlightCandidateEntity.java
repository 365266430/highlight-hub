package com.highlighthub.highlight;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("highlight_candidates")
public class HighlightCandidateEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String highlightRunId;
    private String analysisRunId;
    private String mediaId;
    private Long ownerId;
    private Long startMs;
    private Long endMs;
    private BigDecimal score;
    private String reasonCode;
    private String reasonText;
    private String eventIds;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHighlightRunId() { return highlightRunId; }
    public void setHighlightRunId(String highlightRunId) { this.highlightRunId = highlightRunId; }
    public String getAnalysisRunId() { return analysisRunId; }
    public void setAnalysisRunId(String analysisRunId) { this.analysisRunId = analysisRunId; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Long getStartMs() { return startMs; }
    public void setStartMs(Long startMs) { this.startMs = startMs; }
    public Long getEndMs() { return endMs; }
    public void setEndMs(Long endMs) { this.endMs = endMs; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonText() { return reasonText; }
    public void setReasonText(String reasonText) { this.reasonText = reasonText; }
    public String getEventIds() { return eventIds; }
    public void setEventIds(String eventIds) { this.eventIds = eventIds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
