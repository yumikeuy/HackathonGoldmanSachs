-- V1__init.sql — стартовая схема.
-- Владельцы: sources, teams, users, delivery_outbox, events — Роль 1;
--            rules, alerts — Роль 2; runbooks, audit_logs — Роль 3.
--
-- Условие (condition) везде одного формата — JSON-дерево:
--   {"and":[ {"path":"trade.notional","op":">","value":10000000}, {"or":[...]} ]}

-- ===================== Роль 1: источники, команды, пользователи =====================

CREATE TABLE sources (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,          -- payments-monitoring
    type        VARCHAR(20)  NOT NULL                  -- http | kafka | file
                CHECK (type IN ('http', 'kafka', 'file')),
    config      JSONB        NOT NULL DEFAULT '{}',    -- {"topic": "workflow.events"} и т.п.
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE teams (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,          -- Team Rates
    condition   JSONB,                                 -- какие события относятся к команде; NULL = никакие
    strict      BOOLEAN      NOT NULL DEFAULT FALSE,   -- условия команды и её правил — только точные пути
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id                   BIGSERIAL    PRIMARY KEY,
    team_id              BIGINT       REFERENCES teams(id),   -- в какой команде
    name                 VARCHAR(100) NOT NULL,               -- Anna Kowalska
    email                VARCHAR(200) UNIQUE,                 -- anna@bank.com
    phone                VARCHAR(30),                         -- +48 600 000 000 (SMS и звонки)
    timezone             VARCHAR(50)  NOT NULL DEFAULT 'Europe/Warsaw',
    channel_preferences  JSONB        NOT NULL DEFAULT '[]',  -- [{"channel":"SMS","hours":"08-20"},{"channel":"EMAIL"}]
    working_hours        JSONB,                               -- {"from":"08:00","to":"20:00","days":[1,2,3,4,5]}
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_team ON users (team_id);

-- ===================== Роль 3: runbooks =====================

CREATE TABLE runbooks (
    id         BIGSERIAL    PRIMARY KEY,
    team_id    BIGINT       REFERENCES teams(id),   -- NULL = глобальный
    condition  JSONB,                               -- когда прикреплять к алерту; NULL = только через override в правиле
    file_name  VARCHAR(255) NOT NULL,               -- restart-workflow.pdf; по расширению отдаём Content-Type
    content    BYTEA        NOT NULL                -- файл как есть, внутрь не смотрим
);
CREATE INDEX idx_runbooks_team ON runbooks (team_id);

-- ===================== Роль 2: правила и алерты =====================

-- Правило команды: при каком условии и как оформить и доставить алерт.
CREATE TABLE rules (
    id                   BIGSERIAL    PRIMARY KEY,
    name                 VARCHAR(200) NOT NULL,
    team_id              BIGINT       NOT NULL REFERENCES teams(id),
    condition            JSONB,                                  -- NULL = все события команды
    priority             INT          NOT NULL DEFAULT 100,
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    assigned_severity    VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM'
                         CHECK (assigned_severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    channels             JSONB        NOT NULL DEFAULT '["UI"]', -- ["EMAIL","UI"]
    group_by_paths       JSONB        NOT NULL DEFAULT '[]',     -- ["instrument"]
    group_mode           VARCHAR(12)  NOT NULL DEFAULT 'while_open'
                         CHECK (group_mode IN ('while_open', 'per_window')),
    dedup_window_sec     INT          NOT NULL DEFAULT 300,
    presentation         JSONB        NOT NULL DEFAULT '{}',     -- {"title":"Rejected {{trade.id}} ({{trade.notional}})"}
    resolve_condition    JSONB,                                  -- когда закрывать автоматически
    override_runbook_id  BIGINT       REFERENCES runbooks(id),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_rules_team ON rules (team_id) WHERE is_active;

CREATE TABLE alerts (
    id                 BIGSERIAL    PRIMARY KEY,
    rule_id            BIGINT       REFERENCES rules(id),      -- NULL у неразобранных (UNROUTED)
    team_id            BIGINT       REFERENCES teams(id),
    runbook_id         BIGINT       REFERENCES runbooks(id),   -- выбранный runbook
    fingerprint        CHAR(64)     NOT NULL,                  -- sha256 hex
    severity           VARCHAR(10)  NOT NULL
                       CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN')),
    status             VARCHAR(10)  NOT NULL DEFAULT 'OPEN'
                       CHECK (status IN ('OPEN', 'ACKED', 'RESOLVED')),
    title              VARCHAR(500) NOT NULL,
    occurrences_count  INT          NOT NULL DEFAULT 1,
    sample_payload     JSONB        NOT NULL,                  -- первое событие группы
    first_seen_at      TIMESTAMPTZ  NOT NULL,
    last_seen_at       TIMESTAMPTZ  NOT NULL,
    resolved_at        TIMESTAMPTZ
);
-- Ровно один открытый алерт на fingerprint — это и есть дедупликация на уровне БД
CREATE UNIQUE INDEX uq_alerts_open_fingerprint ON alerts (fingerprint)
    WHERE status IN ('OPEN', 'ACKED');
CREATE INDEX idx_alerts_feed   ON alerts (team_id, last_seen_at DESC, id);
CREATE INDEX idx_alerts_status ON alerts (status, last_seen_at);

-- ===================== Роль 1: события и доставка =====================

-- Сюда попадают только неразобранные и упавшие события, не весь поток
CREATE TABLE events (
    event_id     UUID         PRIMARY KEY,
    source_name  VARCHAR(100) NOT NULL,
    ingested_at  TIMESTAMPTZ  NOT NULL,
    raw_payload  JSONB        NOT NULL,
    status       VARCHAR(10)  NOT NULL CHECK (status IN ('UNROUTED', 'FAILED')),
    error        TEXT,
    alert_id     BIGINT       REFERENCES alerts(id)
);
CREATE INDEX idx_events_status_time ON events (status, ingested_at);

CREATE TABLE delivery_outbox (
    id             BIGSERIAL   PRIMARY KEY,
    alert_id       BIGINT      NOT NULL REFERENCES alerts(id),
    team_id        BIGINT      NOT NULL REFERENCES teams(id),
    user_id        BIGINT      REFERENCES users(id),           -- кому из команды; NULL = только UI-лента команды
    channel        VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'CALL', 'UI')),
    payload        JSONB       NOT NULL,                       -- уже отрендеренный текст под канал
    status         VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    retry_count    INT         NOT NULL DEFAULT 0,
    next_retry_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error     TEXT,
    sent_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Воркер берёт только ждущие задачи: индекс маленький, FOR UPDATE SKIP LOCKED быстрый
CREATE INDEX idx_outbox_pending ON delivery_outbox (next_retry_at) WHERE status = 'PENDING';

-- ===================== Роль 3: аудит =====================

CREATE TABLE audit_logs (
    id           BIGSERIAL    PRIMARY KEY,
    alert_id     BIGINT       REFERENCES alerts(id),
    actor        VARCHAR(100) NOT NULL,                        -- anna@bank.com / system / rule:7
    action_type  VARCHAR(50)  NOT NULL,                        -- ACK, RESOLVE, AUTO_CLOSE, RUNBOOK_ATTACHED …
    details      JSONB        NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_alert_time ON audit_logs (alert_id, created_at);
