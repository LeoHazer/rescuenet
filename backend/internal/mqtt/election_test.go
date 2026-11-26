package mqtt

import "testing"

func validHeartbeat() ElectionMessage {
	return ElectionMessage{
		Type:                 "HEARTBEAT",
		TeamID:               1,
		NodeID:               6029904383848236762,
		EpochCounter:         4,
		EpochInitiatorNodeID: 6029904383848236762,
		Timestamp:            1_700_000_000,
		CoordinatorNodeID:    6029904383848236762,
	}
}

func TestElectionMessageValidate(t *testing.T) {
	tests := []struct {
		name    string
		change  func(*ElectionMessage)
		wantErr bool
	}{
		{"heartbeat válido", func(*ElectionMessage) {}, false},
		{"coordinator válido", func(m *ElectionMessage) { m.Type = "COORDINATOR" }, false},
		{"hello não anuncia liderança", func(m *ElectionMessage) { m.Type = "HELLO" }, true},
		{"equipe inválida", func(m *ElectionMessage) { m.TeamID = 0 }, true},
		{"coordenador diferente do emissor", func(m *ElectionMessage) { m.CoordinatorNodeID = 1 }, true},
		{"época zerada", func(m *ElectionMessage) { m.EpochCounter = 0 }, true},
		{"iniciador inválido", func(m *ElectionMessage) { m.EpochInitiatorNodeID = 0 }, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			message := validHeartbeat()
			tt.change(&message)

			err := message.Validate()
			if (err != nil) != tt.wantErr {
				t.Fatalf("Validate() erro = %v, esperado erro = %v", err, tt.wantErr)
			}
		})
	}
}

func TestValidateElectionTopic(t *testing.T) {
	message := validHeartbeat()

	if err := ValidateElectionTopic("rescuenet/teams/1/election", message); err != nil {
		t.Fatalf("tópico válido rejeitado: %v", err)
	}

	for _, topic := range []string{
		"rescuenet/teams/2/election",
		"rescuenet/teams/1/location",
		"rescuenet/teams/x/election",
		"rescuenet/teams/1/election/extra",
	} {
		if err := ValidateElectionTopic(topic, message); err == nil {
			t.Errorf("tópico %q deveria ser rejeitado", topic)
		}
	}
}

func TestEpochOrderMatchesApp(t *testing.T) {
	if !(Epoch{1, 900}).Less(Epoch{2, 100}) {
		t.Error("contador maior deve prevalecer")
	}
	if !(Epoch{2, 100}).Less(Epoch{2, 200}) {
		t.Error("com o mesmo contador, o iniciador desempata")
	}
	if (Epoch{2, 200}).Less(Epoch{2, 200}) {
		t.Error("épocas iguais não são menores")
	}
}

func TestDecideLeader(t *testing.T) {
	current := &LeaderState{NodeID: 10, Epoch: Epoch{3, 10}}

	tests := []struct {
		name    string
		current *LeaderState
		nodeID  int64
		epoch   Epoch
		want    LeaderDecision
	}{
		{"primeiro líder da equipe", nil, 10, Epoch{1, 10}, LeaderChange},
		{"heartbeat do líder atual", current, 10, Epoch{3, 10}, LeaderRefresh},
		{"outro nó com época mais nova", current, 20, Epoch{4, 20}, LeaderChange},
		{"mesmo nó reeleito em época nova", current, 10, Epoch{5, 10}, LeaderChange},
		{"coordenador antigo em conflito", current, 20, Epoch{1, 20}, LeaderIgnore},
		{
			"líder inativo é substituído mesmo com época menor",
			&LeaderState{NodeID: 10, Epoch: Epoch{3, 10}, Stale: true},
			20, Epoch{1, 20}, LeaderChange,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := DecideLeader(tt.current, tt.nodeID, tt.epoch); got != tt.want {
				t.Fatalf("DecideLeader() = %v, esperado %v", got, tt.want)
			}
		})
	}
}
