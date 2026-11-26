-- RescueNet: localização por aparelho (nó).
--
-- Executado automaticamente pelo docker-compose.yml na primeira vez que o
-- banco é criado, depois de 001_schema_base.sql.

-- Identificador do aparelho que publicou a posição. Fica NULL nas linhas
-- antigas e nas mensagens de versões do aplicativo que não enviam node_id.
ALTER TABLE locations ADD COLUMN IF NOT EXISTS node_id BIGINT;

-- Atende a consulta da última posição por (team_id, node_id), usada em
-- GET /api/nodes/locations/latest.
CREATE INDEX IF NOT EXISTS locations_team_node_recorded_idx
    ON locations (team_id, node_id, recorded_at DESC);
