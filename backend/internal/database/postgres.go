package database

import (
	"context"
	"database/sql"
	"fmt"
	"net"
	"net/url"
	"os"
	"time"

	_ "github.com/lib/pq"
)

// envOrDefault returns an environment variable or its default value.
func envOrDefault(key, fallback string) string {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	return value
}

// Open establishes and verifies a PostgreSQL connection.
func Open() (*sql.DB, error) {
	password := os.Getenv("RESCUENET_DB_PASSWORD")

	if password == "" {
		return nil, fmt.Errorf("RESCUENET_DB_PASSWORD environment variable is not set")
	}

	user := envOrDefault("RESCUENET_DB_USER", "rescuenet_app")
	host := envOrDefault("RESCUENET_DB_HOST", "127.0.0.1")
	port := envOrDefault("RESCUENET_DB_PORT", "5432")
	dbName := envOrDefault("RESCUENET_DB_NAME", "rescuenet")

	connectionURL := &url.URL{
		Scheme: "postgres",
		User:   url.UserPassword(user, password),
		Host:   net.JoinHostPort(host, port),
		Path:   dbName,
	}

	query := connectionURL.Query()
	query.Set("sslmode", "disable")
	connectionURL.RawQuery = query.Encode()

	db, err := sql.Open("postgres", connectionURL.String())
	if err != nil {
		return nil, fmt.Errorf("opening PostgreSQL connection: %w", err)
	}

	db.SetMaxOpenConns(10)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(30 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("connecting to PostgreSQL: %w", err)
	}

	return db, nil
}
