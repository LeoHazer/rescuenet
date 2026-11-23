package mqtt

import "testing"

func TestPresenceMessageValidate(t *testing.T) {
	tests := []struct {
		name    string
		message PresenceMessage
		wantErr bool
	}{
		{
			name: "presenca online valida",
			message: PresenceMessage{
				TeamID: 1, NodeID: 8349847061793163677,
				Status: "online", Timestamp: 1795030921,
			},
			wantErr: false,
		},
		{
			name: "presenca offline valida",
			message: PresenceMessage{
				TeamID: 1, NodeID: 8349847061793163677,
				Status: "offline", Timestamp: 1795030921,
			},
			wantErr: false,
		},
		{
			name: "team_id invalido",
			message: PresenceMessage{
				TeamID: 0, NodeID: 123,
				Status: "online", Timestamp: 1795030921,
			},
			wantErr: true,
		},
		{
			name: "node_id invalido",
			message: PresenceMessage{
				TeamID: 1, NodeID: 0,
				Status: "online", Timestamp: 1795030921,
			},
			wantErr: true,
		},
		{
			name: "status invalido",
			message: PresenceMessage{
				TeamID: 1, NodeID: 123,
				Status: "unknown", Timestamp: 1795030921,
			},
			wantErr: true,
		},
		{
			name: "timestamp invalido",
			message: PresenceMessage{
				TeamID: 1, NodeID: 123,
				Status: "online", Timestamp: 0,
			},
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.message.Validate()

			if (err != nil) != tt.wantErr {
				t.Errorf("Validate() error = %v, wantErr = %v",
					err, tt.wantErr)
			}
		})
	}
}

func TestValidatePresenceTopic(t *testing.T) {
	message := PresenceMessage{
		TeamID: 1, NodeID: 123,
		Status: "online", Timestamp: 1795030921,
	}

	tests := []struct {
		name    string
		topic   string
		message PresenceMessage
		wantErr bool
	}{
		{
			name:    "topico valido",
			topic:   "rescuenet/teams/1/nodes/123/presence",
			message: message,
			wantErr: false,
		},
		{
			name:    "topico com formato invalido",
			topic:   "rescuenet/teams/1/location",
			message: message,
			wantErr: true,
		},
		{
			name:    "team_id diferente do payload",
			topic:   "rescuenet/teams/2/nodes/123/presence",
			message: message,
			wantErr: true,
		},
		{
			name:    "node_id diferente do payload",
			topic:   "rescuenet/teams/1/nodes/456/presence",
			message: message,
			wantErr: true,
		},
		{
			name:    "team_id do topico invalido",
			topic:   "rescuenet/teams/abc/nodes/123/presence",
			message: message,
			wantErr: true,
		},
		{
			name:    "node_id do topico invalido",
			topic:   "rescuenet/teams/1/nodes/abc/presence",
			message: message,
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := ValidatePresenceTopic(tt.topic, tt.message)

			if (err != nil) != tt.wantErr {
				t.Errorf("ValidatePresenceTopic() error = %v, wantErr = %v",
					err, tt.wantErr)
			}
		})
	}
}
