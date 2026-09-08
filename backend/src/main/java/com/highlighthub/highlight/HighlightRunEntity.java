package com.highlighthub.highlight;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("highlight_runs")
public class HighlightRunEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String analysisRunId;
    private String ruleVersionId;
    private String status;
    private String taskId;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAnalysisRunId() { return analysisRunId; }
    public void setAnalysisRunId(String analysisRunId) { this.analysisRunId = analysisRunId; }
    public String getRuleVersionId() { return ruleVersionId; }
    public void setRuleVersionId(String ruleVersionId) { this.ruleVersionId = ruleVersionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
