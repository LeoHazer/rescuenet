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
