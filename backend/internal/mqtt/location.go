package mqtt

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"time"
)

// LocationMessage representa uma localizacao recebida do aplicativo Android.
type LocationMessage struct {
	TeamID    int     `json:"team_id"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
	Accuracy  float64 `json:"accuracy"`
	Battery   int     `json:"battery"`
	Timestamp int64   `json:"timestamp"`
}

// Validate verifica se os dados recebidos sao validos.
func (m LocationMessage) Validate() error {
	if m.TeamID <= 0 {
		return fmt.Errorf("team_id deve ser maior que zero")
	}

	if m.Latitude < -90 || m.Latitude > 90 {
		return fmt.Errorf("latitude fora do intervalo permitido")
	}

	if m.Longitude < -180 || m.Longitude > 180 {
		return fmt.Errorf("longitude fora do intervalo permitido")
	}

	if m.Accuracy < 0 {
		return fmt.Errorf("accuracy nao pode ser negativa")
	}

	if m.Battery < 0 || m.Battery > 100 {
		return fmt.Errorf("battery deve estar entre 0 e 100")
	}

	if m.Timestamp <= 0 {
		return fmt.Errorf("timestamp deve ser maior que zero")
	}

	return nil
}

// HandleLocationMessage interpreta, valida e persiste uma mensagem MQTT.
func HandleLocationMessage(db *sql.DB, topic string, payload []byte) {
	var location LocationMessage

	if err := json.Unmarshal(payload, &location); err != nil {
		log.Printf("[ERRO] JSON invalido recebido em %s: %v", topic, err)
		return
	}

	if err := location.Validate(); err != nil {
		log.Printf("[ERRO] Localizacao invalida em %s: %v", topic, err)
		return
	}

	recordedAt := time.Unix(location.Timestamp, 0).UTC()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	result, err := db.ExecContext(
		ctx,
		`INSERT INTO locations
            (team_id, latitude, longitude, accuracy, battery, recorded_at)
         VALUES ($1, $2, $3, $4, $5, $6)`,
		location.TeamID,
		location.Latitude,
		location.Longitude,
		location.Accuracy,
		location.Battery,
		recordedAt,
	)

	if err != nil {
		log.Printf("[DATABASE] Falha ao salvar localizacao: %v", err)
		return
	}

	rows, err := result.RowsAffected()
	if err != nil {
		log.Printf("[DATABASE] Localizacao inserida, mas nao foi possivel confirmar as linhas: %v", err)
		return
	}

	log.Println("========================================")
	log.Println("[LOCALIZACAO RECEBIDA E SALVA]")
	log.Printf("Topico:     %s", topic)
	log.Printf("Equipe:     %d", location.TeamID)
	log.Printf("Latitude:   %.6f", location.Latitude)
	log.Printf("Longitude:  %.6f", location.Longitude)
	log.Printf("Precisao:   %.2f metros", location.Accuracy)
	log.Printf("Bateria:    %d%%", location.Battery)
	log.Printf("Data/hora:  %s", recordedAt.Format(time.RFC3339))
	log.Printf("Linhas inseridas: %d", rows)
	log.Println("========================================")
}
