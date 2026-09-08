package com.highlighthub.render;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("render_jobs")
public class RenderJobEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private Integer projectRevision;
    private Long ownerId;
    private String mediaId;
    private String presetVersion;
    private String rendererVersion;
    private String status;
    private String taskId;
    private String outputAssetId;
    private Long outputSize;
    private String outputChecksum;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Integer getProjectRevision() { return projectRevision; }
    public void setProjectRevision(Integer projectRevision) { this.projectRevision = projectRevision; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public String getPresetVersion() { return presetVersion; }
    public void setPresetVersion(String presetVersion) { this.presetVersion = presetVersion; }
    public String getRendererVersion() { return rendererVersion; }
    public void setRendererVersion(String rendererVersion) { this.rendererVersion = rendererVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getOutputAssetId() { return outputAssetId; }
    public void setOutputAssetId(String outputAssetId) { this.outputAssetId = outputAssetId; }
    public Long getOutputSize() { return outputSize; }
    public void setOutputSize(Long outputSize) { this.outputSize = outputSize; }
    public String getOutputChecksum() { return outputChecksum; }
    public void setOutputChecksum(String outputChecksum) { this.outputChecksum = outputChecksum; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
