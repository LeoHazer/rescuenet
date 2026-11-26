package mqtt

import "testing"

func TestLocationMessageValidate(t *testing.T) {
	tests := []struct {
		name    string
		message LocationMessage
		wantErr bool
	}{
		{
			name: "localização válida",
			message: LocationMessage{
				TeamID:    1,
				Latitude:  -22.526,
				Longitude: -41.945,
				Accuracy:  8.5,
				Battery:   78,
				Timestamp: 1793816106,
			},
			wantErr: false,
		},
		{
			name: "identificador inválido",
			message: LocationMessage{
				TeamID: 0, Latitude: -22.526,
				Longitude: -41.945, Accuracy: 8.5,
				Battery: 78, Timestamp: 1793816106,
			},
			wantErr: true,
		},
		{
			name: "latitude fora do intervalo",
			message: LocationMessage{
				TeamID: 1, Latitude: 91,
				Longitude: -41.945, Accuracy: 8.5,
				Battery: 78, Timestamp: 1793816106,
			},
			wantErr: true,
		},
		{
			name: "longitude fora do intervalo",
			message: LocationMessage{
				TeamID: 1, Latitude: -22.526,
				Longitude: 181, Accuracy: 8.5,
				Battery: 78, Timestamp: 1793816106,
			},
			wantErr: true,
		},
		{
			name: "precisão negativa",
			message: LocationMessage{
				TeamID: 1, Latitude: -22.526,
				Longitude: -41.945, Accuracy: -1,
				Battery: 78, Timestamp: 1793816106,
			},
			wantErr: true,
		},
		{
			name: "bateria acima de 100",
			message: LocationMessage{
				TeamID: 1, Latitude: -22.526,
				Longitude: -41.945, Accuracy: 8.5,
				Battery: 101, Timestamp: 1793816106,
			},
			wantErr: true,
		},
		{
			name: "timestamp inválido",
			message: LocationMessage{
				TeamID: 1, Latitude: -22.526,
				Longitude: -41.945, Accuracy: 8.5,
				Battery: 78, Timestamp: 0,
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

func TestLocationMessageValidateNodeID(t *testing.T) {
	valid := func(nodeID *int64) LocationMessage {
		return LocationMessage{
			TeamID: 1, NodeID: nodeID, Latitude: -22.5,
			Longitude: -41.9, Accuracy: 5, Battery: 50,
			Timestamp: 1793816106,
		}
	}
	ptr := func(value int64) *int64 { return &value }

	tests := []struct {
		name    string
		nodeID  *int64
		wantErr bool
	}{
		{name: "sem node_id (APK antigo)", nodeID: nil, wantErr: false},
		{name: "node_id positivo", nodeID: ptr(42), wantErr: false},
		{name: "node_id zero", nodeID: ptr(0), wantErr: true},
		{name: "node_id negativo", nodeID: ptr(-7), wantErr: true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := valid(tt.nodeID).Validate()

			if (err != nil) != tt.wantErr {
				t.Errorf("Validate() error = %v, wantErr = %v", err, tt.wantErr)
			}
		})
	}
}

func TestParseLocationMessage(t *testing.T) {
	// Identificador sintético acima de 2^53: precisa chegar sem perda.
	const syntheticNodeID int64 = 9007199254740993

	tests := []struct {
		name       string
		payload    string
		wantErr    bool
		wantNodeID *int64
	}{
		{
			name: "payload sem node_id",
			payload: `{"team_id":1,"latitude":-22.5,"longitude":-41.9,
				"accuracy":5,"battery":50,"timestamp":1793816106}`,
			wantNodeID: nil,
		},
		{
			name: "payload com node_id nulo",
			payload: `{"team_id":1,"node_id":null,"latitude":-22.5,"longitude":-41.9,
				"accuracy":5,"battery":50,"timestamp":1793816106}`,
			wantNodeID: nil,
		},
		{
			name: "payload com node_id de 64 bits",
			payload: `{"team_id":1,"node_id":9007199254740993,"latitude":-22.5,
				"longitude":-41.9,"accuracy":5,"battery":50,"timestamp":1793816106}`,
			wantNodeID: func() *int64 { v := syntheticNodeID; return &v }(),
		},
		{
			name: "payload com node_id zero",
			payload: `{"team_id":1,"node_id":0,"latitude":-22.5,"longitude":-41.9,
				"accuracy":5,"battery":50,"timestamp":1793816106}`,
			wantErr: true,
		},
		{
			name: "payload com node_id negativo",
			payload: `{"team_id":1,"node_id":-1,"latitude":-22.5,"longitude":-41.9,
				"accuracy":5,"battery":50,"timestamp":1793816106}`,
			wantErr: true,
		},
		{
			name:    "node_id como texto",
			payload: `{"team_id":1,"node_id":"42","latitude":-22.5,"longitude":-41.9,"accuracy":5,"battery":50,"timestamp":1793816106}`,
			wantErr: true,
		},
		{
			name:    "JSON inválido",
			payload: `{"team_id":1,`,
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := ParseLocationMessage([]byte(tt.payload))

			if (err != nil) != tt.wantErr {
				t.Fatalf("ParseLocationMessage() error = %v, wantErr = %v", err, tt.wantErr)
			}
			if tt.wantErr {
				return
			}

			switch {
			case tt.wantNodeID == nil && got.NodeID != nil:
				t.Errorf("NodeID = %d, esperado nil", *got.NodeID)
			case tt.wantNodeID != nil && got.NodeID == nil:
				t.Errorf("NodeID = nil, esperado %d", *tt.wantNodeID)
			case tt.wantNodeID != nil && *got.NodeID != *tt.wantNodeID:
				t.Errorf("NodeID = %d, esperado %d", *got.NodeID, *tt.wantNodeID)
			}
		})
	}
}
