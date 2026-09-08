-- V3: worker self-reported disk headroom + process uptime (spec section 21).
ALTER TABLE worker_status ADD COLUMN disk_free_bytes BIGINT NULL;
ALTER TABLE worker_status ADD COLUMN uptime_seconds BIGINT NULL;
