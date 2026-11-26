-- RescueNet: localização por aparelho (nó).
--
-- Execute uma vez no banco "rescuenet", com um usuário administrador,
-- depois do schema base (teams, locations, ...):
--
--   psql -h 127.0.0.1 -U postgres -d rescuenet -f backend/migrations/localizacao_por_no.sql
--
-- O script é idempotente: pode ser executado novamente sem efeito colateral.

-- Identificador do aparelho que publicou a posição. Fica NULL nas linhas
-- antigas e nas mensagens de versões do aplicativo que não enviam node_id.
ALTER TABLE locations ADD COLUMN IF NOT EXISTS node_id BIGINT;

-- Atende a consulta da última posição por (team_id, node_id), usada em
-- GET /api/nodes/locations/latest.
CREATE INDEX IF NOT EXISTS locations_team_node_recorded_idx
    ON locations (team_id, node_id, recorded_at DESC);

-- Permissões para o usuário da aplicação, se ele existir.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rescuenet_app') THEN
        GRANT SELECT, INSERT ON locations TO rescuenet_app;
    END IF;
END
$$;
