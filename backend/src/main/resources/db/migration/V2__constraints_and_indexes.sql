-- V2__constraints_and_indexes.sql — integrity and performance on top of V1.
-- NO new tables or columns: only constraints, foreign-key delete behaviour,
-- indexes on foreign keys and sequence steps for batched inserts.

-- ===================== 1. A user belongs to exactly one team =====================
-- AGENTS.md: "a user is a member of exactly one team". V1 left the column nullable.
ALTER TABLE users ALTER COLUMN team_id SET NOT NULL;

-- ===================== 2. Foreign-key behaviour on delete =====================
-- Policy:
--   * configuration (teams, rules) cannot be deleted while referenced — RESTRICT (as in V1);
--     use rules.is_active / users.is_active to switch things off instead;
--   * a runbook is just a file: it may be deleted, references to it become NULL;
--   * everything owned by an alert (outbox, audit) is deleted with it — otherwise the hourly
--     cleanup of RESOLVED alerts would be blocked by these FKs;
--   * events outlive their alert: they are the journal of unrouted/failed messages.

ALTER TABLE rules
    DROP CONSTRAINT rules_override_runbook_id_fkey,
    ADD  CONSTRAINT rules_override_runbook_id_fkey
         FOREIGN KEY (override_runbook_id) REFERENCES runbooks (id) ON DELETE SET NULL;

ALTER TABLE alerts
    DROP CONSTRAINT alerts_runbook_id_fkey,
    ADD  CONSTRAINT alerts_runbook_id_fkey
         FOREIGN KEY (runbook_id) REFERENCES runbooks (id) ON DELETE SET NULL;

ALTER TABLE events
    DROP CONSTRAINT events_alert_id_fkey,
    ADD  CONSTRAINT events_alert_id_fkey
         FOREIGN KEY (alert_id) REFERENCES alerts (id) ON DELETE SET NULL;

ALTER TABLE delivery_outbox
    DROP CONSTRAINT delivery_outbox_alert_id_fkey,
    ADD  CONSTRAINT delivery_outbox_alert_id_fkey
         FOREIGN KEY (alert_id) REFERENCES alerts (id) ON DELETE CASCADE,
    DROP CONSTRAINT delivery_outbox_user_id_fkey,
    ADD  CONSTRAINT delivery_outbox_user_id_fkey
         FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE audit_logs
    DROP CONSTRAINT audit_logs_alert_id_fkey,
    ADD  CONSTRAINT audit_logs_alert_id_fkey
         FOREIGN KEY (alert_id) REFERENCES alerts (id) ON DELETE CASCADE;

-- ===================== 3. Indexes on foreign keys =====================
-- Postgres does not create them automatically. Without them every delete of an alert/runbook/user
-- seq-scans the child tables. Partial where the reference is usually NULL.
CREATE INDEX idx_alerts_rule        ON alerts (rule_id)              WHERE rule_id IS NOT NULL;
CREATE INDEX idx_alerts_runbook     ON alerts (runbook_id)           WHERE runbook_id IS NOT NULL;
CREATE INDEX idx_rules_override_rb  ON rules (override_runbook_id)   WHERE override_runbook_id IS NOT NULL;
CREATE INDEX idx_events_alert       ON events (alert_id)             WHERE alert_id IS NOT NULL;
CREATE INDEX idx_outbox_alert       ON delivery_outbox (alert_id);
CREATE INDEX idx_outbox_user        ON delivery_outbox (user_id)     WHERE user_id IS NOT NULL;
-- Hourly cleanup: "SENT outbox rows older than 1 day"
CREATE INDEX idx_outbox_sent        ON delivery_outbox (sent_at)     WHERE status = 'SENT';

-- ===================== 4. Invariant checks =====================
-- JSONB shape: reject garbage on write rather than when loading the cache.
ALTER TABLE sources
    ADD CONSTRAINT chk_sources_config_object
        CHECK (jsonb_typeof(config) = 'object');

ALTER TABLE users
    ADD CONSTRAINT chk_users_channel_preferences_array
        CHECK (jsonb_typeof(channel_preferences) = 'array'),
    ADD CONSTRAINT chk_users_working_hours_object
        CHECK (working_hours IS NULL OR jsonb_typeof(working_hours) = 'object');

ALTER TABLE rules
    ADD CONSTRAINT chk_rules_dedup_window_positive
        CHECK (dedup_window_sec > 0),
    ADD CONSTRAINT chk_rules_channels_array
        CHECK (jsonb_typeof(channels) = 'array'),
    ADD CONSTRAINT chk_rules_group_by_paths_array
        CHECK (jsonb_typeof(group_by_paths) = 'array'),
    ADD CONSTRAINT chk_rules_presentation_object
        CHECK (jsonb_typeof(presentation) = 'object');

ALTER TABLE alerts
    ADD CONSTRAINT chk_alerts_occurrences_positive
        CHECK (occurrences_count >= 1),
    ADD CONSTRAINT chk_alerts_seen_order
        CHECK (last_seen_at >= first_seen_at),
    -- resolved_at is set if and only if the alert is resolved
    ADD CONSTRAINT chk_alerts_resolved_at
        CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)),
    -- UNROUTED alert: no rule and severity UNKNOWN; a rule-based alert gets its severity from the rule
    ADD CONSTRAINT chk_alerts_unrouted_shape
        CHECK ((rule_id IS NULL) = (severity = 'UNKNOWN'));

ALTER TABLE delivery_outbox
    ADD CONSTRAINT chk_outbox_retry_count
        CHECK (retry_count >= 0),
    -- UI is one row per team (user_id NULL); every other channel targets a specific user
    ADD CONSTRAINT chk_outbox_ui_has_no_user
        CHECK ((channel = 'UI') = (user_id IS NULL));

-- ===================== 5. Sequences for batched inserts =====================
-- delivery_outbox and audit_logs are written in batches in the same transaction as the alert.
-- Hibernate cannot batch INSERTs with IDENTITY, so these entities use
-- @SequenceGenerator(allocationSize = 50) — the sequence step must match it.
-- Column DEFAULT nextval(...) stays, so hand-written INSERTs keep working.
ALTER SEQUENCE delivery_outbox_id_seq INCREMENT BY 50;
ALTER SEQUENCE audit_logs_id_seq      INCREMENT BY 50;
