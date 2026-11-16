package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
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

	return mux
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
			"status": "unavailable",
			"database": "disconnected",
		})
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{
		"status": "ok",
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