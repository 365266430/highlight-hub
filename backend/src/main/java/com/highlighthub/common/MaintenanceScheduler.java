package com.highlighthub.common;

import com.highlighthub.upload.UploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceScheduler {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);

    private final UploadService uploadService;

    public MaintenanceScheduler(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    /** release quota + delete temp chunks for expired/abandoned upload sessions */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void sweepUploadSessions() {
        try {
            uploadService.sweepExpired();
        } catch (Exception e) {
            log.warn("upload session sweep failed: {}", e.toString());
        }
    }
}
