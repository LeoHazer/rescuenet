package database

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"time"

	_ "github.com/lib/pq"
)

// Conexão fixa com o PostgreSQL do docker-compose.yml da raiz do projeto.
// Projeto acadêmico de demonstração: os valores ficam no código de propósito.
// A porta 5433 evita conflito com um PostgreSQL já instalado no Windows.
const connectionString = "postgres://rescuenet:rescuenet@127.0.0.1:5433/rescuenet?sslmode=disable"

const (
	connectAttempts      = 30
	delayBetweenAttempts = 2 * time.Second
)

// Open conecta ao PostgreSQL. Se o banco ainda estiver iniciando (por
// exemplo, logo após "docker compose up"), tenta novamente por até um minuto.
func Open() (*sql.DB, error) {
	db, err := sql.Open("postgres", connectionString)
	if err != nil {
		return nil, fmt.Errorf("opening PostgreSQL connection: %w", err)
	}

	db.SetMaxOpenConns(10)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(30 * time.Minute)

	for attempt := 1; ; attempt++ {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		err = db.PingContext(ctx)
		cancel()

		if err == nil {
			return db, nil
		}

		if attempt == connectAttempts {
			db.Close()
			return nil, fmt.Errorf(
				"connecting to PostgreSQL (o banco está rodando? use \"docker compose up -d\" na raiz do projeto): %w",
				err,
			)
		}

		log.Printf("[DATABASE] Banco indisponível, nova tentativa em %s (%d/%d): %v",
			delayBetweenAttempts, attempt, connectAttempts, err)
		time.Sleep(delayBetweenAttempts)
	}
}
