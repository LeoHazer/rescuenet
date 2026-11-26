package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"

	rescuemqtt "github.com/LeoHazer/rescuenet/backend/internal/mqtt"
)

type Handler struct {
	db *sql.DB
}

func NewHandler(db *sql.DB) http.Handler {
	h := &Handler{db: db}
	mux := http.NewServeMux()

	mux.HandleFunc("/health", h.health)
	mux.HandleFunc("/api/teams", h.teams)
	mux.HandleFunc("/api/teams/", h.teamLocations)
	mux.HandleFunc("/api/locations/latest", h.latestLocations)
	mux.HandleFunc("/api/nodes/locations/latest", h.latestNodeLocations)
	mux.HandleFunc("/api/nodes/", h.nodeLocations)
	mux.HandleFunc("/api/leaders", h.leaders)
	mux.HandleFunc("/api/leaders/history", h.leaderHistory)
	mux.HandleFunc("/api/presence", h.presence)

	return mux
}

// parseLimit lê o parâmetro opcional ?limit= (1 a 200).
func parseLimit(r *http.Request, fallback int) (int, bool) {
	value := r.URL.Query().Get("limit")
	if value == "" {
		return fallback, true
	}

	parsed, err := strconv.Atoi(value)
	if err != nil || parsed < 1 || parsed > 200 {
		return 0, false
	}

	return parsed, true
}

func writeJSON(w http.ResponseWriter, status int, data any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func methodNotAllowed(w http.ResponseWriter) {
	w.Header().Set("Allow", http.MethodGet)
	writeJSON(w, http.StatusMethodNotAllowed, map[string]string{
		"error": "method not allowed",
	})
}

func (h *Handler) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()

	if err := h.db.PingContext(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{
			"status":   "unavailable",
			"database": "disconnected",
		})
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{
		"status":   "ok",
		"database": "connected",
	})
}

func (h *Handler) teams(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}

	rows, err := h.db.QueryContext(
		r.Context(),
		`SELECT id, name, created_at
		 FROM teams
		 ORDER BY id`,
	)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to query teams",
		})
		return
	}
	defer rows.Close()

	type Team struct {
		ID        int       `json:"id"`
		Name      string    `json:"name"`
		CreatedAt time.Time `json:"created_at"`
	}

	result := make([]Team, 0)

	for rows.Next() {
		var team Team

		if err := rows.Scan(&team.ID, &team.Name, &team.CreatedAt); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{
				"error": "failed to read teams",
			})
			return
		}

		result = append(result, team)
	}

	if err := rows.Err(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to read teams",
		})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

func (h *Handler) teamLocations(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}

	path := strings.TrimPrefix(r.URL.Path, "/api/teams/")
	parts := strings.Split(path, "/")

	if len(parts) == 2 && parts[1] == "presence" {
		h.teamPresence(w, r, parts[0])
		return
	}

	if len(parts) == 3 && parts[1] == "presence" && parts[2] == "history" {
		h.teamPresenceHistory(w, r, parts[0])
		return
	}
	if len(parts) != 2 || parts[1] != "locations" {
		http.NotFound(w, r)
		return
	}

	teamID, err := strconv.Atoi(parts[0])
	if err != nil || teamID <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "invalid team id",
		})
		return
	}

	limit := 50

	if value := r.URL.Query().Get("limit"); value != "" {
		parsed, err := strconv.Atoi(value)
		if err != nil || parsed < 1 || parsed > 200 {
			writeJSON(w, http.StatusBadRequest, map[string]string{
				"error": "limit must be between 1 and 200",
			})
			return
		}
		limit = parsed
	}

	rows, err := h.db.QueryContext(
		r.Context(),
		`SELECT id, team_id, latitude, longitude, accuracy, battery, recorded_at
		 FROM locations
		 WHERE team_id = $1
		 ORDER BY recorded_at DESC, id DESC
		 LIMIT $2`,
		teamID,
		limit,
	)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to query locations",
		})
		return
	}
	defer rows.Close()

	type Location struct {
		ID         int64     `json:"id"`
		TeamID     int       `json:"team_id"`
		Latitude   float64   `json:"latitude"`
		Longitude  float64   `json:"longitude"`
		Accuracy   float64   `json:"accuracy"`
		Battery    int       `json:"battery"`
		RecordedAt time.Time `json:"recorded_at"`
	}

	result := make([]Location, 0)

	for rows.Next() {
		var location Location

		if err := rows.Scan(
			&location.ID,
			&location.TeamID,
			&location.Latitude,
			&location.Longitude,
			&location.Accuracy,
			&location.Battery,
			&location.RecordedAt,
		); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{
				"error": "failed to read locations",
			})
			return
		}

		result = append(result, location)
	}

	if err := rows.Err(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to read locations",
		})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

func (h *Handler) latestLocations(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}

	rows, err := h.db.QueryContext(
		r.Context(),
		`SELECT DISTINCT ON (team_id)
		     id, team_id, latitude, longitude, accuracy, battery, recorded_at
		 FROM locations
		 ORDER BY team_id, recorded_at DESC, id DESC`,
	)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to query latest locations",
		})
		return
	}
	defer rows.Close()

	type Location struct {
		ID         int64     `json:"id"`
		TeamID     int       `json:"team_id"`
		Latitude   float64   `json:"latitude"`
		Longitude  float64   `json:"longitude"`
		Accuracy   float64   `json:"accuracy"`
		Battery    int       `json:"battery"`
		RecordedAt time.Time `json:"recorded_at"`
	}

	result := make([]Location, 0)

	for rows.Next() {
		var location Location

		if err := rows.Scan(
			&location.ID,
			&location.TeamID,
			&location.Latitude,
			&location.Longitude,
			&location.Accuracy,
			&location.Battery,
			&location.RecordedAt,
		); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{
				"error": "failed to read latest locations",
			})
			return
		}

		result = append(result, location)
	}

	if err := rows.Err(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to read latest locations",
		})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

// latestNodeLocations devolve a última posição de cada aparelho, por
// (team_id, node_id). Linhas sem node_id (APKs antigos ou registros
// anteriores à migração localizacao_por_no.sql) ficam de fora, pois não
// identificam um aparelho; elas continuam em /api/locations/latest.
// O node_id é serializado como texto (inteiro de 64 bits, acima de 2^53).
func (h *Handler) latestNodeLocations(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}

	rows, err := h.db.QueryContext(
		r.Context(),
		`SELECT DISTINCT ON (team_id, node_id)
       id, team_id, node_id, latitude, longitude, accuracy, battery, recorded_at
  FROM locations
 WHERE node_id IS NOT NULL
 ORDER BY team_id, node_id, recorded_at DESC, id DESC`,
	)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to query latest node locations",
		})
		return
	}
	defer rows.Close()

	type NodeLocation struct {
		ID         int64     `json:"id"`
		TeamID     int       `json:"team_id"`
		NodeID     int64     `json:"node_id,string"`
		Latitude   float64   `json:"latitude"`
		Longitude  float64   `json:"longitude"`
		Accuracy   float64   `json:"accuracy"`
		Battery    int       `json:"battery"`
		RecordedAt time.Time `json:"recorded_at"`
	}

	result := make([]NodeLocation, 0)

	for rows.Next() {
		var location NodeLocation

		if err := rows.Scan(
			&location.ID,
			&location.TeamID,
			&location.NodeID,
			&location.Latitude,
			&location.Longitude,
			&location.Accuracy,
			&location.Battery,
			&location.RecordedAt,
		); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{
				"error": "failed to read latest node locations",
			})
			return
		}

		result = append(result, location)
	}

	if err := rows.Err(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to read latest node locations",
		})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

// parseNodeLocationsPath extrai o node_id de "/api/nodes/{node_id}/locations".
func parseNodeLocationsPath(path string) (int64, bool) {
	parts := strings.Split(strings.TrimPrefix(path, "/api/nodes/"), "/")
	if len(parts) != 2 || parts[1] != "locations" {
		return 0, false
	}

	nodeID, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil || nodeID <= 0 {
		return 0, false
	}

	return nodeID, true
}

// nodeLocations devolve o histórico de posições de um aparelho, da mais
// recente para a mais antiga: GET /api/nodes/{node_id}/locations?limit=1..200
// (padrão 50). O node_id da URL e da resposta é texto (64 bits).
func (h *Handler) nodeLocations(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}

	nodeID, ok := parseNodeLocationsPath(r.URL.Path)
	if !ok {
		http.NotFound(w, r)
		return
	}

	limit, ok := parseLimit(r, 50)
	if !ok {
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "limit must be between 1 and 200",
		})
		return
	}

	rows, err := h.db.QueryContext(
		r.Context(),
		`SELECT id, team_id, node_id, latitude, longitude, accuracy, battery, recorded_at
  FROM locations
 WHERE node_id = $1
 ORDER BY recorded_at DESC, id DESC
 LIMIT $2`,
		nodeID,
		limit,
	)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to query node locations",
		})
		return
	}
	defer rows.Close()

	type NodeLocation struct {
		ID         int64     `json:"id"`
		TeamID     int       `json:"team_id"`
		NodeID     int64     `json:"node_id,string"`
		Latitude   float64   `json:"latitude"`
		Longitude  float64   `json:"longitude"`
		Accuracy   float64   `json:"accuracy"`
		Battery    int       `json:"battery"`
		RecordedAt time.Time `json:"recorded_at"`
	}

	result := make([]NodeLocation, 0)

	for rows.Next() {
		var location NodeLocation

		if err := rows.Scan(
			&location.ID,
			&location.TeamID,
			&location.NodeID,
			&location.Latitude,
			&location.Longitude,
			&location.Accuracy,
			&location.Battery,
			&location.RecordedAt,
		); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{
				"error": "failed to read node locations",
			})
			return
		}

		result = append(result, location)
	}

	if err := rows.Err(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to read node locations",
		})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

func (h *Handler) teamPresence(w http.ResponseWriter, r *http.Request, teamIDPath string) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}

	teamID, err := strconv.Atoi(teamIDPath)
	if err != nil || teamID <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "invalid team id",
		})
		return
	}

	rows, err := h.db.QueryContext(
		r.Context(),
		`SELECT team_id, node_id, status, event_timestamp, received_at
                 FROM node_presence
                 WHERE team_id = $1
                 ORDER BY received_at DESC, node_id`,
		teamID,
	)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to query team presence",
		})
		return
	}
	defer rows.Close()

	type NodePresence struct {
		TeamID         int       `json:"team_id"`
		NodeID         int64     `json:"node_id"`
		Status         string    `json:"status"`
		EventTimestamp time.Time `json:"event_timestamp"`
		ReceivedAt     time.Time `json:"received_at"`
	}

	result := make([]NodePresence, 0)

	for rows.Next() {
		var presence NodePresence

		if err := rows.Scan(
			&presence.TeamID,
			&presence.NodeID,
			&presence.Status,
			&presence.EventTimestamp,
			&presence.ReceivedAt,
		); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{
				"error": "failed to read team presence",
			})
			return
		}

		result = append(result, presence)
	}

	if err := rows.Err(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to read team presence",
		})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

func (h *Handler) teamPresenceHistory(w http.ResponseWriter, r *http.Request, teamIDPath string) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}

	teamID, err := strconv.Atoi(teamIDPath)
	if err != nil || teamID <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "invalid team id",
		})
		return
	}

	limit := 50

	if value := r.URL.Query().Get("limit"); value != "" {
		parsed, err := strconv.Atoi(value)
		if err != nil || parsed < 1 || parsed > 200 {
			writeJSON(w, http.StatusBadRequest, map[string]string{
				"error": "limit must be between 1 and 200",
			})
			return
		}
		limit = parsed
	}

	rows, err := h.db.QueryContext(
		r.Context(),
		`SELECT id, team_id, node_id, status, event_timestamp, received_at
                 FROM presence_history
                 WHERE team_id = $1
                 ORDER BY received_at DESC, id DESC
                 LIMIT $2`,
		teamID,
		limit,
	)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to query presence history",
		})
		return
	}
	defer rows.Close()

	type PresenceEvent struct {
		ID             int64     `json:"id"`
		TeamID         int       `json:"team_id"`
		NodeID         int64     `json:"node_id"`
		Status         string    `json:"status"`
		EventTimestamp time.Time `json:"event_timestamp"`
		ReceivedAt     time.Time `json:"received_at"`
	}

	result := make([]PresenceEvent, 0)

	for rows.Next() {
		var event PresenceEvent

		if err := rows.Scan(
			&event.ID,
			&event.TeamID,
			&event.NodeID,
			&event.Status,
			&event.EventTimestamp,
			&event.ReceivedAt,
		); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{
				"error": "failed to read presence history",
			})
			return
		}

		result = append(result, event)
	}

	if err := rows.Err(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to read presence history",
		})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

// Os identificadores de nó são inteiros de 64 bits, acima do limite exato
// de números em JavaScript (2^53). Nas rotas abaixo, consumidas pelo
// dashboard, eles são serializados como texto.

func (h *Handler) leaders(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}

	// Um líder é ativo se anunciou liderança recentemente e sua presença
	// não está marcada como offline (LWT).
	rows, err := h.db.QueryContext(
		r.Context(),
		`SELECT l.team_id, l.node_id, l.epoch_counter, l.epoch_initiator_node_id,
       l.elected_at, l.last_seen_at,
       l.last_seen_at >= now() - make_interval(secs => $1)
         AND COALESCE(p.status, 'online') <> 'offline' AS active
  FROM team_leaders l
  LEFT JOIN node_presence p
         ON p.team_id = l.team_id AND p.node_id = l.node_id
 ORDER BY l.team_id`,
		rescuemqtt.LeaderTimeoutSeconds,
	)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to query leaders",
		})
		return
	}
	defer rows.Close()

	type Leader struct {
		TeamID               int       `json:"team_id"`
		NodeID               int64     `json:"node_id,string"`
		EpochCounter         int64     `json:"epoch_counter"`
		EpochInitiatorNodeID int64     `json:"epoch_initiator_node_id,string"`
		ElectedAt            time.Time `json:"elected_at"`
		LastSeenAt           time.Time `json:"last_seen_at"`
		Active               bool      `json:"active"`
	}

	result := make([]Leader, 0)

	for rows.Next() {
		var leader Leader

		if err := rows.Scan(
			&leader.TeamID,
			&leader.NodeID,
			&leader.EpochCounter,
			&leader.EpochInitiatorNodeID,
			&leader.ElectedAt,
			&leader.LastSeenAt,
			&leader.Active,
		); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{
				"error": "failed to read leaders",
			})
			return
		}

		result = append(result, leader)
	}

	if err := rows.Err(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to read leaders",
		})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

func (h *Handler) leaderHistory(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}

	limit, ok := parseLimit(r, 20)
	if !ok {
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "limit must be between 1 and 200",
		})
		return
	}

	rows, err := h.db.QueryContext(
		r.Context(),
		`SELECT id, team_id, node_id, epoch_counter, epoch_initiator_node_id,
       source_type, received_at
  FROM leader_history
 ORDER BY received_at DESC, id DESC
 LIMIT $1`,
		limit,
	)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to query leader history",
		})
		return
	}
	defer rows.Close()

	type LeaderEvent struct {
		ID                   int64     `json:"id"`
		TeamID               int       `json:"team_id"`
		NodeID               int64     `json:"node_id,string"`
		EpochCounter         int64     `json:"epoch_counter"`
		EpochInitiatorNodeID int64     `json:"epoch_initiator_node_id,string"`
		SourceType           string    `json:"source_type"`
		ReceivedAt           time.Time `json:"received_at"`
	}

	result := make([]LeaderEvent, 0)

	for rows.Next() {
		var event LeaderEvent

		if err := rows.Scan(
			&event.ID,
			&event.TeamID,
			&event.NodeID,
			&event.EpochCounter,
			&event.EpochInitiatorNodeID,
			&event.SourceType,
			&event.ReceivedAt,
		); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{
				"error": "failed to read leader history",
			})
			return
		}

		result = append(result, event)
	}

	if err := rows.Err(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to read leader history",
		})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

func (h *Handler) presence(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}

	rows, err := h.db.QueryContext(
		r.Context(),
		`SELECT team_id, node_id, status, event_timestamp, received_at
  FROM node_presence
 ORDER BY team_id, status DESC, received_at DESC`,
	)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to query presence",
		})
		return
	}
	defer rows.Close()

	type NodePresence struct {
		TeamID         int       `json:"team_id"`
		NodeID         int64     `json:"node_id,string"`
		Status         string    `json:"status"`
		EventTimestamp time.Time `json:"event_timestamp"`
		ReceivedAt     time.Time `json:"received_at"`
	}

	result := make([]NodePresence, 0)

	for rows.Next() {
		var presence NodePresence

		if err := rows.Scan(
			&presence.TeamID,
			&presence.NodeID,
			&presence.Status,
			&presence.EventTimestamp,
			&presence.ReceivedAt,
		); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{
				"error": "failed to read presence",
			})
			return
		}

		result = append(result, presence)
	}

	if err := rows.Err(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{
			"error": "failed to read presence",
		})
		return
	}

	writeJSON(w, http.StatusOK, result)
}
