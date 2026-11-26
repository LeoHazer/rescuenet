-- RescueNet: tabelas de liderança (coordenador atual e histórico).
--
-- Executado automaticamente pelo docker-compose.yml na primeira vez que o
-- banco é criado, depois de 001_schema_base.sql.

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
