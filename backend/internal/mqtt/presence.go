package mqtt

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strconv"
	"strings"
	"time"
)

// PresenceMessage representa o estado de um dispositivo Android.
type PresenceMessage struct {
	TeamID    int    `json:"team_id"`
	NodeID    int64  `json:"node_id"`
	Status    string `json:"status"`
	Timestamp int64  `json:"timestamp"`
}

// Validate verifica os dados recebidos.
func (m PresenceMessage) Validate() error {
	if m.TeamID <= 0 {
		return fmt.Errorf("team_id deve ser maior que zero")
	}

	if m.NodeID <= 0 {
		return fmt.Errorf("node_id deve ser maior que zero")
	}

	if m.Status != "online" && m.Status != "offline" {
		return fmt.Errorf("status deve ser online ou offline")
	}

	if m.Timestamp <= 0 {
		return fmt.Errorf("timestamp deve ser maior que zero")
	}

	return nil
}

// ValidatePresenceTopic verifica se o tópico corresponde aos IDs do payload.
func ValidatePresenceTopic(topic string, message PresenceMessage) error {
	parts := strings.Split(topic, "/")

	if len(parts) != 6 ||
		parts[0] != "rescuenet" ||
		parts[1] != "teams" ||
		parts[3] != "nodes" ||
		parts[5] != "presence" {
		return fmt.Errorf("formato de tópico de presença inválido")
	}

	teamID, err := strconv.Atoi(parts[2])
	if err != nil || teamID != message.TeamID {
		return fmt.Errorf("team_id do tópico não corresponde ao payload")
	}

	nodeID, err := strconv.ParseInt(parts[4], 10, 64)
	if err != nil || nodeID != message.NodeID {
		return fmt.Errorf("node_id do tópico não corresponde ao payload")
	}

	return nil
}

// HandlePresenceMessage valida e persiste um evento de presença.
func HandlePresenceMessage(db *sql.DB, topic string, payload []byte, retained bool) {
	var message PresenceMessage

	if err := json.Unmarshal(payload, &message); err != nil {
		log.Printf("[ERRO] JSON de presença inválido em %s: %v", topic, err)
		return
	}

	if err := message.Validate(); err != nil {
		log.Printf("[ERRO] Presença inválida em %s: %v", topic, err)
		return
	}

	if err := ValidatePresenceTopic(topic, message); err != nil {
		log.Printf("[ERRO] Tópico de presença inconsistente em %s: %v", topic, err)
		return
	}

	eventTimestamp := time.Unix(message.Timestamp, 0).UTC()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		log.Printf("[DATABASE] Falha ao iniciar transação de presença: %v", err)
		return
	}
	defer tx.Rollback()

	var receivedAt time.Time

	err = tx.QueryRowContext(
		ctx,
		`INSERT INTO presence_history
(team_id, node_id, status, event_timestamp)
 VALUES ($1, $2, $3, $4)
 ON CONFLICT (team_id, node_id, status, event_timestamp)
 DO NOTHING
 RETURNING received_at`,
		message.TeamID,
		message.NodeID,
		message.Status,
		eventTimestamp,
	).Scan(&receivedAt)

	if errors.Is(err, sql.ErrNoRows) {
		log.Printf(
			"[MQTT] Evento de presença duplicado ignorado: equipe=%d dispositivo=%d status=%s",
			message.TeamID, message.NodeID, message.Status,
		)
		return
	}

	if err != nil {
		log.Printf("[DATABASE] Falha ao registrar histórico de presença: %v", err)
		return
	}

	var result sql.Result

	if retained {
		// Uma mensagem retida pode inicializar um dispositivo ainda ausente,
		// mas não deve sobrescrever um estado já registrado.
		result, err = tx.ExecContext(
			ctx,
			`INSERT INTO node_presence
(team_id, node_id, status, event_timestamp, received_at)
 VALUES ($1, $2, $3, $4, $5)
 ON CONFLICT (team_id, node_id) DO NOTHING`,
			message.TeamID,
			message.NodeID,
			message.Status,
			eventTimestamp,
			receivedAt,
		)
	} else {
		result, err = tx.ExecContext(
			ctx,
			`INSERT INTO node_presence
(team_id, node_id, status, event_timestamp, received_at)
 VALUES ($1, $2, $3, $4, $5)
 ON CONFLICT (team_id, node_id)
 DO UPDATE SET
status = EXCLUDED.status,
event_timestamp = EXCLUDED.event_timestamp,
received_at = EXCLUDED.received_at
 WHERE node_presence.received_at < EXCLUDED.received_at`,
			message.TeamID,
			message.NodeID,
			message.Status,
			eventTimestamp,
			receivedAt,
		)
	}

	if err != nil {
		log.Printf("[DATABASE] Falha ao atualizar estado de presença: %v", err)
		return
	}

	if err := tx.Commit(); err != nil {
		log.Printf("[DATABASE] Falha ao confirmar transação de presença: %v", err)
		return
	}

	rows, err := result.RowsAffected()
	if err != nil {
		log.Printf("[DATABASE] Presença salva, mas não foi possível confirmar a atualização: %v", err)
		return
	}

	log.Println("========================================")
	log.Println("[PRESENÇA RECEBIDA E SALVA]")
	log.Printf("Tópico:       %s", topic)
	log.Printf("Equipe:       %d", message.TeamID)
	log.Printf("Dispositivo:  %d", message.NodeID)
	log.Printf("Status:       %s", message.Status)
	log.Printf("Data/hora:    %s", eventTimestamp.Format(time.RFC3339))
	log.Printf("Retida:       %t", retained)
	log.Printf("Estado atual atualizado: %t", rows > 0)
	log.Println("========================================")
}
