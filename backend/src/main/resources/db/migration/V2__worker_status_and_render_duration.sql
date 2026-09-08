-- V2: observability (spec section 21) + worker liveness.
-- Render real-time ratio needs the measured output duration per job.
ALTER TABLE render_jobs ADD COLUMN output_duration_ms BIGINT NULL;

-- Worker liveness: heartbeats upserted by the worker loop; "online" =
-- last_heartbeat within a freshness window computed by the stats query.
CREATE TABLE worker_status (
    worker_id      VARCHAR(64)  PRIMARY KEY,
    last_heartbeat DATETIME(3)  NOT NULL,
    active_task_id VARCHAR(36)  NULL,
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL
) ENGINE = InnoDB;
