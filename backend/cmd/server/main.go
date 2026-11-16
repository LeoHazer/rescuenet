package main

import (
"context"
"errors"
"log"
"net/http"
"os"
"os/signal"
"syscall"
"time"

rescuedb "github.com/LeoHazer/rescuenet/backend/internal/database"
rescueapi "github.com/LeoHazer/rescuenet/backend/internal/api"
rescuemqtt "github.com/LeoHazer/rescuenet/backend/internal/mqtt"
pahomqtt "github.com/eclipse/paho.mqtt.golang"
)

const (
brokerURL = "tcp://127.0.0.1:1883"
topic     = "rescuenet/teams/+/location"
httpAddr  = "127.0.0.1:8080"
)

func main() {
log.Println("========================================")
log.Println(" RescueNet - Backend Go")
log.Println("========================================")

log.Println("[DATABASE] Conectando ao PostgreSQL...")
db, err := rescuedb.Open()
if err != nil {
log.Fatalf("[ERRO] Falha ao conectar ao PostgreSQL: %v", err)
}
log.Println("[DATABASE] Conexão estabelecida com sucesso.")

apiServer := &http.Server{
Addr:              httpAddr,
Handler:           rescueapi.NewHandler(db),
ReadHeaderTimeout: 5 * time.Second,
}

httpErrors := make(chan error, 1)

go func() {
log.Printf("[HTTP] API REST ouvindo em http://%s", httpAddr)
if err := apiServer.ListenAndServe(); err != nil &&
!errors.Is(err, http.ErrServerClosed) {
httpErrors <- err
}
}()

log.Printf("[MQTT] Broker: %s", brokerURL)
log.Printf("[MQTT] Tópico: %s", topic)

options := pahomqtt.NewClientOptions()
options.AddBroker(brokerURL)
options.SetClientID("rescuenet-go-backend")
options.SetCleanSession(true)
options.SetAutoReconnect(true)
options.SetConnectRetry(true)
options.SetConnectRetryInterval(5 * time.Second)
options.SetKeepAlive(30 * time.Second)

options.SetDefaultPublishHandler(func(client pahomqtt.Client, message pahomqtt.Message) {
rescuemqtt.HandleLocationMessage(db, message.Topic(), message.Payload())
})

options.SetOnConnectHandler(func(client pahomqtt.Client) {
log.Println("[MQTT] Conectado ao broker.")
token := client.Subscribe(topic, 1, nil)

if !token.WaitTimeout(10 * time.Second) {
log.Println("[ERRO] Timeout ao assinar o tópico MQTT.")
return
}

if err := token.Error(); err != nil {
log.Printf("[ERRO] Falha ao assinar o tópico MQTT: %v", err)
return
}

log.Printf("[MQTT] Inscrito no tópico: %s", topic)
})

options.SetConnectionLostHandler(func(client pahomqtt.Client, err error) {
log.Printf("[MQTT] Conexão perdida: %v", err)
})

client := pahomqtt.NewClient(options)

log.Println("[MQTT] Tentando conectar...")
token := client.Connect()

if !token.WaitTimeout(10 * time.Second) {
client.Disconnect(250)
_ = apiServer.Close()
_ = db.Close()
log.Fatal("[ERRO] Timeout ao conectar ao broker MQTT.")
}

if err := token.Error(); err != nil {
client.Disconnect(250)
_ = apiServer.Close()
_ = db.Close()
log.Fatalf("[ERRO] Falha ao conectar ao broker MQTT: %v", err)
}

log.Println("[BACKEND] PostgreSQL e MQTT conectados.")
log.Println("[BACKEND] Serviço iniciado. Aguardando mensagens...")

signals := make(chan os.Signal, 1)
signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
defer signal.Stop(signals)

select {
case sig := <-signals:
log.Printf("[BACKEND] Sinal recebido: %v", sig)
case err := <-httpErrors:
log.Printf("[ERRO] Falha no servidor HTTP: %v", err)
}

log.Println("[BACKEND] Encerrando serviço...")

shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
defer cancel()

if err := apiServer.Shutdown(shutdownCtx); err != nil {
log.Printf("[HTTP] Erro ao encerrar API REST: %v", err)
_ = apiServer.Close()
} else {
log.Println("[HTTP] API REST encerrada.")
}

client.Disconnect(250)

if err := db.Close(); err != nil {
log.Printf("[DATABASE] Erro ao fechar conexão: %v", err)
} else {
log.Println("[DATABASE] Conexão encerrada.")
}

log.Println("[BACKEND] Serviço encerrado.")
}
