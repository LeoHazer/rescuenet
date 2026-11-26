package api

import "testing"

func TestParseNodeLocationsPath(t *testing.T) {
	tests := []struct {
		path   string
		wantID int64
		wantOK bool
	}{
		// IDs sintéticos; o primeiro passa de 2^53 e não pode perder precisão.
		{"/api/nodes/9007199254740993/locations", 9007199254740993, true},
		{"/api/nodes/42/locations", 42, true},
		{"/api/nodes/0/locations", 0, false},
		{"/api/nodes/-5/locations", 0, false},
		{"/api/nodes/abc/locations", 0, false},
		{"/api/nodes/42", 0, false},
		{"/api/nodes/42/locations/extra", 0, false},
		{"/api/nodes/42/presence", 0, false},
		{"/api/nodes/99999999999999999999/locations", 0, false},
	}

	for _, tt := range tests {
		t.Run(tt.path, func(t *testing.T) {
			gotID, gotOK := parseNodeLocationsPath(tt.path)

			if gotOK != tt.wantOK || gotID != tt.wantID {
				t.Errorf("parseNodeLocationsPath(%q) = (%d, %v), esperado (%d, %v)",
					tt.path, gotID, gotOK, tt.wantID, tt.wantOK)
			}
		})
	}
}
