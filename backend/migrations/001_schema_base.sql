-- RescueNet: schema base do banco (tabelas usadas pelo backend Go).
--
-- Executado automaticamente pelo docker-compose.yml na primeira vez que o
-- banco é criado (os scripts desta pasta rodam em ordem: 001, 002, 003).
--
-- Reconstruído a partir das consultas do backend
-- (internal/mqtt/location.go, internal/mqtt/presence.go, internal/api/http.go).

-- Equipes de resgate.
CREATE TABLE IF NOT EXISTS teams (
    id         SERIAL      PRIMARY KEY,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Histórico de localizações publicadas pelos aparelhos.
CREATE TABLE IF NOT EXISTS locations (
    id          BIGSERIAL        PRIMARY KEY,
    team_id     INTEGER          NOT NULL REFERENCES teams (id),
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    accuracy    DOUBLE PRECISION NOT NULL,
    battery     INTEGER          NOT NULL,
    recorded_at TIMESTAMPTZ      NOT NULL
);

CREATE INDEX IF NOT EXISTS locations_team_recorded_idx
    ON locations (team_id, recorded_at DESC);

-- Estado atual de presença de cada aparelho (online/offline, via LWT).
CREATE TABLE IF NOT EXISTS node_presence (
    team_id         INTEGER     NOT NULL,
    node_id         BIGINT      NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('online', 'offline')),
    event_timestamp TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (team_id, node_id)
);

-- Histórico de eventos de presença (sem duplicatas da mesma mensagem).
CREATE TABLE IF NOT EXISTS presence_history (
    id              BIGSERIAL   PRIMARY KEY,
    team_id         INTEGER     NOT NULL,
    node_id         BIGINT      NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('online', 'offline')),
    event_timestamp TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (team_id, node_id, status, event_timestamp)
);

-- O aplicativo usa a equipe 1 (MqttConfig.TEAM_ID).
INSERT INTO teams (id, name) VALUES (1, 'Equipe 1')
ON CONFLICT (id) DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('teams', 'id'),
    GREATEST((SELECT max(id) FROM teams), 1)
);
