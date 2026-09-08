package com.highlighthub.adapter;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("adapter_versions")
public class AdapterVersionEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String adapterId;
    private Integer adapterVersion;
    private String templateVersion;
    private String status;
    private String supportedLayouts;
    private String supportedResolutions;
    private String supportedLanguages;
    private String supportedEventTypes;
    private String configJson;
    private String notes;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAdapterId() { return adapterId; }
    public void setAdapterId(String adapterId) { this.adapterId = adapterId; }
    public Integer getAdapterVersion() { return adapterVersion; }
    public void setAdapterVersion(Integer adapterVersion) { this.adapterVersion = adapterVersion; }
    public String getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(String templateVersion) { this.templateVersion = templateVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSupportedLayouts() { return supportedLayouts; }
    public void setSupportedLayouts(String supportedLayouts) { this.supportedLayouts = supportedLayouts; }
    public String getSupportedResolutions() { return supportedResolutions; }
    public void setSupportedResolutions(String supportedResolutions) { this.supportedResolutions = supportedResolutions; }
    public String getSupportedLanguages() { return supportedLanguages; }
    public void setSupportedLanguages(String supportedLanguages) { this.supportedLanguages = supportedLanguages; }
    public String getSupportedEventTypes() { return supportedEventTypes; }
    public void setSupportedEventTypes(String supportedEventTypes) { this.supportedEventTypes = supportedEventTypes; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
