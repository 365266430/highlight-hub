-- HighlightHub initial schema. All business times stored in UTC (DATETIME(3)).
-- Video positions are always milliseconds.

CREATE TABLE users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(32)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    display_name    VARCHAR(64)  NULL,
    role            VARCHAR(16)  NOT NULL DEFAULT 'USER',
    storage_quota_bytes BIGINT   NOT NULL DEFAULT 2147483648,
    used_bytes      BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB;

CREATE TABLE upload_sessions (
    id                  CHAR(36)     PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    original_filename   VARCHAR(255) NOT NULL,
    declared_size       BIGINT       NOT NULL,
    chunk_size          BIGINT       NOT NULL,
    expected_chunk_count INT         NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    reserved_bytes      BIGINT       NOT NULL DEFAULT 0,
    received_bytes      BIGINT       NOT NULL DEFAULT 0,
    media_id            CHAR(36)     NULL,
    expires_at          DATETIME(3)  NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    KEY idx_upload_sessions_user (user_id, status),
    KEY idx_upload_sessions_expiry (status, expires_at)
) ENGINE = InnoDB;

CREATE TABLE upload_chunks (
    id           BIGINT      AUTO_INCREMENT PRIMARY KEY,
    upload_id    CHAR(36)    NOT NULL,
    chunk_index  INT         NOT NULL,
    actual_size  BIGINT      NOT NULL,
    checksum     CHAR(64)    NOT NULL,
    storage_key  VARCHAR(512) NOT NULL,
    created_at   DATETIME(3) NOT NULL,
    UNIQUE KEY uk_upload_chunks (upload_id, chunk_index)
) ENGINE = InnoDB;

CREATE TABLE media (
    id                   CHAR(36)     PRIMARY KEY,
    owner_id             BIGINT       NOT NULL,
    original_filename    VARCHAR(255) NOT NULL,
    content_hash         CHAR(64)     NOT NULL,
    file_size            BIGINT       NOT NULL,
    storage_key          VARCHAR(512) NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    duration_ms          BIGINT       NULL,
    width                INT          NULL,
    height               INT          NULL,
    video_codec          VARCHAR(64)  NULL,
    audio_codec          VARCHAR(64)  NULL,
    frame_rate           DECIMAL(10,4) NULL,
    variable_frame_rate  TINYINT      NULL,
    rotation             INT          NOT NULL DEFAULT 0,
    audio_stream_count   INT          NOT NULL DEFAULT 0,
    probe_json           JSON         NULL,
    thumbnail_json       JSON         NULL,
    error_message        VARCHAR(500) NULL,
    created_at           DATETIME(3)  NOT NULL,
    updated_at           DATETIME(3)  NOT NULL,
    KEY idx_media_owner (owner_id, status, created_at),
    KEY idx_media_owner_hash (owner_id, content_hash)
) ENGINE = InnoDB;

CREATE TABLE media_assets (
    id          CHAR(36)     PRIMARY KEY,
    owner_id    BIGINT       NOT NULL,
    media_id    CHAR(36)     NULL,
    type        VARCHAR(16)  NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    size        BIGINT       NOT NULL,
    checksum    CHAR(64)     NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(3)  NOT NULL,
    expires_at  DATETIME(3)  NULL,
    KEY idx_media_assets_media (media_id, type),
    KEY idx_media_assets_owner (owner_id),
    KEY idx_media_assets_expiry (status, expires_at)
) ENGINE = InnoDB;

CREATE TABLE adapter_definitions (
    id           CHAR(36)     PRIMARY KEY,
    game_key     VARCHAR(64)  NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_adapter_definitions_game (game_key)
) ENGINE = InnoDB;

CREATE TABLE adapter_versions (
    id                      CHAR(36)    PRIMARY KEY,
    adapter_id              CHAR(36)    NOT NULL,
    adapter_version         INT         NOT NULL,
    template_version        VARCHAR(32) NOT NULL,
    status                  VARCHAR(16) NOT NULL,
    supported_layouts       JSON        NULL,
    supported_resolutions   JSON        NULL,
    supported_languages     JSON        NULL,
    supported_event_types   JSON        NULL,
    config_json             JSON        NULL,
    notes                   VARCHAR(500) NULL,
    created_at              DATETIME(3) NOT NULL,
    UNIQUE KEY uk_adapter_versions (adapter_id, adapter_version)
) ENGINE = InnoDB;

CREATE TABLE analysis_runs (
    id                   CHAR(36)    PRIMARY KEY,
    media_id             CHAR(36)    NOT NULL,
    owner_id             BIGINT      NOT NULL,
    adapter_version_id   CHAR(36)    NULL,
    mode                 VARCHAR(16) NOT NULL,
    params_json          JSON        NULL,
    params_hash          CHAR(64)    NULL,
    algorithm_version    VARCHAR(32) NOT NULL,
    status               VARCHAR(16) NOT NULL,
    task_id              CHAR(36)    NULL,
    created_at           DATETIME(3) NOT NULL,
    finished_at          DATETIME(3) NULL,
    KEY idx_analysis_runs_media (media_id, created_at),
    KEY idx_analysis_runs_owner (owner_id)
) ENGINE = InnoDB;

CREATE TABLE video_events (
    id                CHAR(36)     PRIMARY KEY,
    analysis_run_id   CHAR(36)     NULL,
    media_id          CHAR(36)     NOT NULL,
    owner_id          BIGINT       NOT NULL,
    type              VARCHAR(32)  NOT NULL,
    start_ms          BIGINT       NOT NULL,
    end_ms            BIGINT       NULL,
    actor             VARCHAR(128) NULL,
    target            VARCHAR(128) NULL,
    confidence        DECIMAL(6,4) NULL,
    source            VARCHAR(8)   NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    evidence_asset_id CHAR(36)     NULL,
    attributes        JSON         NULL,
    adapter_version_id CHAR(36)    NULL,
    created_at        DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    KEY idx_video_events_media (media_id, start_ms),
    KEY idx_video_events_run (analysis_run_id)
) ENGINE = InnoDB;

CREATE TABLE event_revisions (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    event_id    CHAR(36)     NOT NULL,
    revision    INT          NOT NULL,
    changed_by  BIGINT       NOT NULL,
    change_json JSON         NOT NULL,
    note        VARCHAR(500) NULL,
    created_at  DATETIME(3)  NOT NULL,
    KEY idx_event_revisions_event (event_id, revision)
) ENGINE = InnoDB;

CREATE TABLE highlight_rule_versions (
    id          CHAR(36)     PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    version     INT          NOT NULL,
    params_json JSON         NOT NULL,
    params_hash CHAR(64)     NOT NULL,
    created_by  BIGINT       NULL,
    created_at  DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_highlight_rules (name, version)
) ENGINE = InnoDB;

CREATE TABLE highlight_runs (
    id               CHAR(36)    PRIMARY KEY,
    analysis_run_id  CHAR(36)    NOT NULL,
    rule_version_id  CHAR(36)    NOT NULL,
    status           VARCHAR(16) NOT NULL,
    task_id          CHAR(36)    NULL,
    created_at       DATETIME(3) NOT NULL,
    finished_at      DATETIME(3) NULL,
    KEY idx_highlight_runs_analysis (analysis_run_id)
) ENGINE = InnoDB;

CREATE TABLE highlight_candidates (
    id               CHAR(36)      PRIMARY KEY,
    highlight_run_id CHAR(36)      NOT NULL,
    analysis_run_id  CHAR(36)      NOT NULL,
    media_id         CHAR(36)      NOT NULL,
    owner_id         BIGINT        NOT NULL,
    start_ms         BIGINT        NOT NULL,
    end_ms           BIGINT        NOT NULL,
    score            DECIMAL(8,2)  NULL,
    reason_code      VARCHAR(32)   NOT NULL,
    reason_text      VARCHAR(500)  NOT NULL,
    event_ids        JSON          NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    created_at       DATETIME(3)   NOT NULL,
    updated_at       DATETIME(3)   NOT NULL,
    KEY idx_highlight_candidates_run (highlight_run_id, start_ms)
) ENGINE = InnoDB;

CREATE TABLE editing_projects (
    id              CHAR(36)     PRIMARY KEY,
    owner_id        BIGINT       NOT NULL,
    media_id        CHAR(36)     NOT NULL,
    name            VARCHAR(128) NOT NULL,
    latest_revision INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    KEY idx_editing_projects_owner (owner_id, status, updated_at)
) ENGINE = InnoDB;

CREATE TABLE editing_project_revisions (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    project_id        CHAR(36)     NOT NULL,
    revision          INT          NOT NULL,
    schema_version    INT          NOT NULL,
    edit_decision_json JSON        NOT NULL,
    created_at        DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_project_revisions (project_id, revision)
) ENGINE = InnoDB;

CREATE TABLE render_jobs (
    id               CHAR(36)     PRIMARY KEY,
    project_id       CHAR(36)     NOT NULL,
    project_revision INT          NOT NULL,
    owner_id         BIGINT       NOT NULL,
    media_id         CHAR(36)     NOT NULL,
    preset_version   VARCHAR(32)  NOT NULL,
    renderer_version VARCHAR(32)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    task_id          CHAR(36)     NULL,
    output_asset_id  CHAR(36)     NULL,
    output_size      BIGINT       NULL,
    output_checksum  CHAR(64)     NULL,
    error_code       VARCHAR(64)  NULL,
    error_message    VARCHAR(500) NULL,
    created_at       DATETIME(3)  NOT NULL,
    finished_at      DATETIME(3)  NULL,
    KEY idx_render_jobs_owner (owner_id, created_at),
    KEY idx_render_jobs_project (project_id)
) ENGINE = InnoDB;

CREATE TABLE tasks (
    id                 CHAR(36)     PRIMARY KEY,
    owner_id           BIGINT       NULL,
    type               VARCHAR(24)  NOT NULL,
    input_ref          VARCHAR(64)  NOT NULL,
    input_version      VARCHAR(64)  NULL,
    payload_json       JSON         NULL,
    status             VARCHAR(20)  NOT NULL,
    attempt            INT          NOT NULL DEFAULT 0,
    max_attempts       INT          NOT NULL DEFAULT 3,
    attempt_token      VARCHAR(64)  NULL,
    worker_id          VARCHAR(64)  NULL,
    lease_until        DATETIME(3)  NULL,
    progress           INT          NOT NULL DEFAULT 0,
    phase              VARCHAR(64)  NULL,
    error_code         VARCHAR(64)  NULL,
    error_message      VARCHAR(500) NULL,
    output_ref         VARCHAR(64)  NULL,
    cancel_requested_at DATETIME(3) NULL,
    next_run_at        DATETIME(3)  NOT NULL,
    created_at         DATETIME(3)  NOT NULL,
    started_at         DATETIME(3)  NULL,
    finished_at        DATETIME(3)  NULL,
    updated_at         DATETIME(3)  NOT NULL,
    KEY idx_tasks_claim (type, status, next_run_at),
    KEY idx_tasks_owner (owner_id, created_at),
    KEY idx_tasks_lease (status, lease_until)
) ENGINE = InnoDB;

CREATE TABLE task_attempts (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    task_id        CHAR(36)     NOT NULL,
    attempt        INT          NOT NULL,
    attempt_token  VARCHAR(64)  NOT NULL,
    worker_id      VARCHAR(64)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    error_code     VARCHAR(64)  NULL,
    error_message  VARCHAR(500) NULL,
    started_at     DATETIME(3)  NOT NULL,
    heartbeat_at   DATETIME(3)  NULL,
    finished_at    DATETIME(3)  NULL,
    UNIQUE KEY uk_task_attempts (task_id, attempt),
    KEY idx_task_attempts_token (attempt_token)
) ENGINE = InnoDB;

CREATE TABLE idempotency_records (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    operation     VARCHAR(64)  NOT NULL,
    idem_key      VARCHAR(128) NOT NULL,
    request_hash  CHAR(64)     NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    resource_type VARCHAR(32)  NULL,
    resource_id   VARCHAR(64)  NULL,
    response_json JSON         NULL,
    created_at    DATETIME(3)  NOT NULL,
    expires_at    DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_idempotency (user_id, operation, idem_key),
    KEY idx_idempotency_expiry (expires_at)
) ENGINE = InnoDB;

CREATE TABLE share_links (
    id             CHAR(36)    PRIMARY KEY,
    render_job_id  CHAR(36)    NOT NULL,
    owner_id       BIGINT      NOT NULL,
    token_hash     CHAR(64)    NOT NULL,
    expires_at     DATETIME(3) NOT NULL,
    revoked_at     DATETIME(3) NULL,
    created_at     DATETIME(3) NOT NULL,
    UNIQUE KEY uk_share_token (token_hash)
) ENGINE = InnoDB;

CREATE TABLE outbox_events (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    event_id         CHAR(36)     NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    aggregate_id     VARCHAR(64)  NOT NULL,
    aggregate_version INT         NOT NULL DEFAULT 0,
    payload_json     JSON         NULL,
    created_at       DATETIME(3)  NOT NULL,
    published_at     DATETIME(3)  NULL,
    publish_attempts INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_outbox_event (event_id),
    KEY idx_outbox_unpublished (published_at, id)
) ENGINE = InnoDB;

CREATE TABLE consumed_events (
    event_id      CHAR(36)    NOT NULL,
    consumer_name VARCHAR(64) NOT NULL,
    consumed_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (event_id, consumer_name)
) ENGINE = InnoDB;
