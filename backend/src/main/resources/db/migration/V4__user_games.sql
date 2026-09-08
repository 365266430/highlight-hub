-- V4: user-owned game profiles (users set up their own games, e.g. MOBA/FPS).
-- A profile is a calibration container: display name + genre preset + default
-- ROI template. It references the generic OCR adapter engine; genre presets
-- only guide calibration, they never claim verified recognition.
CREATE TABLE user_games (
    id           CHAR(36)     PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    genre        VARCHAR(32)  NOT NULL DEFAULT 'other',
    default_rois JSON         NULL,
    notes        VARCHAR(500) NULL,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    KEY idx_user_games_user (user_id, created_at)
) ENGINE = InnoDB;
