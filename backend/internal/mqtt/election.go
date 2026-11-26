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

// LeaderTimeoutSeconds é o tempo sem COORDINATOR/HEARTBEAT após o qual o
// líder registrado é considerado inativo. Os nós enviam heartbeat a cada
// 5 s e iniciam nova eleição após 15 s de silêncio.
const LeaderTimeoutSeconds = 20

// ElectionMessage representa as mensagens do tópico de eleição que
// interessam ao backend: COORDINATOR e HEARTBEAT.
type ElectionMessage struct {
	Type                 string `json:"type"`
	TeamID               int    `json:"team_id"`
	NodeID               int64  `json:"node_id"`
	EpochCounter         int64  `json:"epoch_counter"`
	EpochInitiatorNodeID int64  `json:"epoch_initiator_node_id"`
	Timestamp            int64  `json:"timestamp"`
	CoordinatorNodeID    int64  `json:"coordinator_node_id"`
}

// Epoch identifica uma rodada de eleição. A ordem é a mesma do aplicativo:
// primeiro o contador, depois o nó que iniciou a rodada.
type Epoch struct {
	Counter         int64
	InitiatorNodeID int64
}

// Less informa se a época e é anterior a other.
func (e Epoch) Less(other Epoch) bool {
	if e.Counter != other.Counter {
		return e.Counter < other.Counter
	}
	return e.InitiatorNodeID < other.InitiatorNodeID
}

// Epoch retorna a época anunciada na mensagem.
func (m ElectionMessage) Epoch() Epoch {
	return Epoch{Counter: m.EpochCounter, InitiatorNodeID: m.EpochInitiatorNodeID}
}

// IsLeadershipAnnouncement indica se a mensagem anuncia um coordenador.
func (m ElectionMessage) IsLeadershipAnnouncement() bool {
	return m.Type == "COORDINATOR" || m.Type == "HEARTBEAT"
}

// Validate verifica um anúncio de liderança.
func (m ElectionMessage) Validate() error {
	if !m.IsLeadershipAnnouncement() {
		return fmt.Errorf("tipo %q não anuncia liderança", m.Type)
	}
	if m.TeamID <= 0 {
		return fmt.Errorf("team_id deve ser maior que zero")
	}
	if m.NodeID <= 0 {
		return fmt.Errorf("node_id deve ser maior que zero")
	}
	if m.CoordinatorNodeID != m.NodeID {
		return fmt.Errorf("coordinator_node_id deve ser o próprio emissor")
	}
	if m.EpochCounter <= 0 || m.EpochInitiatorNodeID <= 0 {
		return fmt.Errorf("época inválida")
	}
	return nil
}

// ValidateElectionTopic verifica se o tópico corresponde à equipe do payload.
func ValidateElectionTopic(topic string, message ElectionMessage) error {
	parts := strings.Split(topic, "/")

	if len(parts) != 4 ||
		parts[0] != "rescuenet" ||
		parts[1] != "teams" ||
		parts[3] != "election" {
		return fmt.Errorf("formato de tópico de eleição inválido")
	}

	teamID, err := strconv.Atoi(parts[2])
	if err != nil || teamID != message.TeamID {
		return fmt.Errorf("team_id do tópico não corresponde ao payload")
	}

	return nil
}

// LeaderState é o líder registrado para uma equipe.
type LeaderState struct {
	NodeID int64
	Epoch  Epoch
	// Stale indica que o líder não se manifesta há mais de LeaderTimeoutSeconds.
	Stale bool
}

// LeaderDecision é o efeito de um anúncio sobre o líder registrado.
type LeaderDecision int

const (
	// LeaderIgnore: anúncio de época antiga (por exemplo, de um nó que
	// ainda não cedeu em um conflito); o líder registrado é mantido.
	LeaderIgnore LeaderDecision = iota
	// LeaderRefresh: heartbeat do líder já registrado.
	LeaderRefresh
	// LeaderChange: novo líder (ou nova época do mesmo nó).
	LeaderChange
)

// DecideLeader aplica a mesma regra dos nós: prevalece a época mais nova.
// Um líder inativo pode ser substituído por qualquer anúncio, o que cobre
// o caso de todos os aplicativos reiniciarem e as épocas recomeçarem.
func DecideLeader(current *LeaderState, nodeID int64, epoch Epoch) LeaderDecision {
	if current == nil {
		return LeaderChange
	}
	if current.NodeID == nodeID && current.Epoch == epoch {
		return LeaderRefresh
	}
	if current.Epoch.Less(epoch) || current.Stale {
		return LeaderChange
	}
	return LeaderIgnore
}

// HandleElectionMessage registra o coordenador atual de cada equipe a
// partir dos anúncios COORDINATOR e HEARTBEAT. As demais mensagens do
// protocolo (HELLO, ELECTION, OK) são ignoradas.
func HandleElectionMessage(db *sql.DB, topic string, payload []byte) {
	var message ElectionMessage

	if err := json.Unmarshal(payload, &message); err != nil {
		log.Printf("[ERRO] JSON de eleição inválido em %s: %v", topic, err)
		return
	}

	if !message.IsLeadershipAnnouncement() {
		return
	}

	if err := message.Validate(); err != nil {
		log.Printf("[ERRO] Anúncio de liderança inválido em %s: %v", topic, err)
		return
	}

	if err := ValidateElectionTopic(topic, message); err != nil {
		log.Printf("[ERRO] Tópico de eleição inconsistente em %s: %v", topic, err)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		log.Printf("[DATABASE] Falha ao iniciar transação de liderança: %v", err)
		return
	}
	defer tx.Rollback()

	var stored LeaderState
	var current *LeaderState

	err = tx.QueryRowContext(
		ctx,
		`SELECT node_id, epoch_counter, epoch_initiator_node_id,
       last_seen_at < now() - make_interval(secs => $2)
  FROM team_leaders
 WHERE team_id = $1
   FOR UPDATE`,
		message.TeamID,
		LeaderTimeoutSeconds,
	).Scan(&stored.NodeID, &stored.Epoch.Counter, &stored.Epoch.InitiatorNodeID, &stored.Stale)

	switch {
	case errors.Is(err, sql.ErrNoRows):
		current = nil
	case err != nil:
		log.Printf("[DATABASE] Falha ao consultar líder atual: %v", err)
		return
	default:
		current = &stored
	}

	decision := DecideLeader(current, message.NodeID, message.Epoch())

	switch decision {
	case LeaderIgnore:
		log.Printf(
			"[MQTT] Anúncio de liderança antigo ignorado: equipe=%d dispositivo=%d época=%d",
			message.TeamID, message.NodeID, message.EpochCounter,
		)
		return

	case LeaderRefresh:
		_, err = tx.ExecContext(
			ctx,
			`UPDATE team_leaders SET last_seen_at = now() WHERE team_id = $1`,
			message.TeamID,
		)

	case LeaderChange:
		_, err = tx.ExecContext(
			ctx,
			`INSERT INTO team_leaders
       (team_id, node_id, epoch_counter, epoch_initiator_node_id, elected_at, last_seen_at)
VALUES ($1, $2, $3, $4, now(), now())
ON CONFLICT (team_id) DO UPDATE SET
       node_id = EXCLUDED.node_id,
       epoch_counter = EXCLUDED.epoch_counter,
       epoch_initiator_node_id = EXCLUDED.epoch_initiator_node_id,
       elected_at = EXCLUDED.elected_at,
       last_seen_at = EXCLUDED.last_seen_at`,
			message.TeamID,
			message.NodeID,
			message.EpochCounter,
			message.EpochInitiatorNodeID,
		)

		if err == nil {
			_, err = tx.ExecContext(
				ctx,
				`INSERT INTO leader_history
       (team_id, node_id, epoch_counter, epoch_initiator_node_id, source_type)
VALUES ($1, $2, $3, $4, $5)`,
				message.TeamID,
				message.NodeID,
				message.EpochCounter,
				message.EpochInitiatorNodeID,
				message.Type,
			)
		}
	}

	if err != nil {
		log.Printf("[DATABASE] Falha ao registrar liderança: %v", err)
		return
	}

	if err := tx.Commit(); err != nil {
		log.Printf("[DATABASE] Falha ao confirmar transação de liderança: %v", err)
		return
	}

	if decision == LeaderChange {
		log.Println("========================================")
		log.Println("[NOVO COORDENADOR REGISTRADO]")
		log.Printf("Tópico:       %s", topic)
		log.Printf("Equipe:       %d", message.TeamID)
		log.Printf("Coordenador:  %d", message.NodeID)
		log.Printf("Época:        %d (iniciada por %d)", message.EpochCounter, message.EpochInitiatorNodeID)
		log.Printf("Origem:       %s", message.Type)
		log.Println("========================================")
	}
}
