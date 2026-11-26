-- RescueNet: tabelas de liderança (coordenador atual e histórico).
--
-- Execute uma vez no banco "rescuenet", com um usuário administrador,
-- depois do schema base (teams, locations, node_presence, presence_history):
--
--   psql -h 127.0.0.1 -U postgres -d rescuenet -f backend/migrations/lideranca.sql
--
-- O script é idempotente: pode ser executado novamente sem efeito colateral.

-- Coordenador atual de cada equipe, mantido a partir dos anúncios
-- COORDINATOR e HEARTBEAT do tópico rescuenet/teams/{id}/election.
CREATE TABLE IF NOT EXISTS team_leaders (
    team_id                 INTEGER     PRIMARY KEY CHECK (team_id > 0),
    node_id                 BIGINT      NOT NULL CHECK (node_id > 0),
    epoch_counter           BIGINT      NOT NULL CHECK (epoch_counter > 0),
    epoch_initiator_node_id BIGINT      NOT NULL CHECK (epoch_initiator_node_id > 0),
    elected_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Uma linha por troca de coordenador (ou nova época do mesmo coordenador).
CREATE TABLE IF NOT EXISTS leader_history (
    id                      BIGSERIAL   PRIMARY KEY,
    team_id                 INTEGER     NOT NULL CHECK (team_id > 0),
    node_id                 BIGINT      NOT NULL CHECK (node_id > 0),
    epoch_counter           BIGINT      NOT NULL CHECK (epoch_counter > 0),
    epoch_initiator_node_id BIGINT      NOT NULL CHECK (epoch_initiator_node_id > 0),
    source_type             TEXT        NOT NULL CHECK (source_type IN ('COORDINATOR', 'HEARTBEAT')),
    received_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS leader_history_received_idx
    ON leader_history (received_at DESC, id DESC);

-- Permissões para o usuário da aplicação, se ele existir.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rescuenet_app') THEN
        GRANT SELECT, INSERT, UPDATE ON team_leaders TO rescuenet_app;
        GRANT SELECT, INSERT ON leader_history TO rescuenet_app;
        GRANT USAGE, SELECT ON SEQUENCE leader_history_id_seq TO rescuenet_app;
    END IF;
END
$$;
