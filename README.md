# RescueNet

Sistema distribuído de monitoramento e comunicação entre nós, com aplicação Android, backend em Go, comunicação MQTT e dashboard web.

Este documento apresenta o procedimento para clonar o projeto, preparar o ambiente de desenvolvimento no Windows, executar os módulos e validar o funcionamento por meio dos testes automatizados.

> **Branch de referência:** `feat/sprint-4-robustez`

---

## 1. Visão geral do projeto

O RescueNet é composto por três módulos principais:

| Módulo | Tecnologia | Responsabilidade |
|---|---|---|
| Aplicativo móvel | Kotlin, Android e Jetpack Compose | Identificação dos nós, localização, comunicação MQTT e eleição de coordenador |
| Backend | Go, PostgreSQL e MQTT | Recepção de mensagens, persistência de dados e API REST |
| Dashboard | Svelte, TypeScript, Vite e Leaflet | Visualização das informações recebidas pelo backend |

### Funcionalidades implementadas

- Comunicação entre dispositivos por MQTT.
- Identificação persistente dos nós da rede.
- Publicação automática e periódica da localização GPS (a cada ~10 s) e do estado de presença.
- Detecção de desconexão por Last Will and Testament (LWT).
- Histórico de presença dos nós.
- Eleição adaptativa de coordenador baseada em critérios de desempenho e disponibilidade.
- Mecanismos de estabilidade e prevenção de trocas desnecessárias de coordenador.
- Backend com API REST e persistência PostgreSQL.
- Dashboard web para visualização das informações, com um marcador por aparelho no mapa e o coordenador ativo destacado.

### Estrutura do repositório

```text
RescueNet/
├── app/                  # Aplicativo Android
├── backend/              # Backend em Go
│   ├── cmd/server/       # Inicialização do servidor
│   └── internal/         # API, MQTT e banco de dados
├── dashboard/            # Interface web Svelte
├── simulator/            # Simulador de nós (JVM), controlado pelo dashboard
├── tools/                # Nó simulado em Python para cenários roteirizados
├── gradle/               # Configuração do Gradle Wrapper
├── registros tecnicos/   # Documentação técnica versionada parcialmente
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 2. Pré-requisitos

Antes de clonar o projeto, instale as ferramentas abaixo.

### 2.1. Git

Necessário para clonar o repositório e acompanhar as alterações.

Download: https://git-scm.com/downloads

Verifique a instalação:

```powershell
git --version
```

### 2.2. Android Studio e Android SDK

Download: https://developer.android.com/studio

Após instalar o Android Studio:

1. Abra o Android Studio.
2. Acesse **Tools → SDK Manager**.
3. Instale o Android SDK Platform 37.
4. Instale o Android SDK Build-Tools compatível.
5. Instale o Android SDK Platform-Tools.
6. Acesse **Tools → Device Manager**.
7. Crie um dispositivo virtual Android para executar o aplicativo.

O projeto utiliza:

- Android Gradle Plugin 9.4.1.
- Gradle 9.6.0, disponibilizado pelo Gradle Wrapper.
- Kotlin 2.2.10.
- Compile SDK 37.
- Minimum SDK 26.

Utilize uma versão do Android Studio compatível com o Android Gradle Plugin do projeto e um JDK 17 ou superior. Confira em **Settings → Build, Execution, Deployment → Build Tools → Gradle** se o Gradle JDK está corretamente configurado.

Não é necessário instalar o Gradle globalmente: o projeto possui Gradle Wrapper.

### 2.3. Go

O backend utiliza Go Modules.

Download: https://go.dev/dl/

A versão declarada no módulo é Go 1.21.6. Recomenda-se utilizar uma versão compatível ou superior.

Verifique:

```powershell
go version
```

### 2.4. Node.js e npm

Necessários para executar o dashboard.

Download: https://nodejs.org/

O projeto utiliza Vite 8, que requer Node.js 20.19+ ou 22.12+.

Verifique:

```powershell
node --version
npm --version
```

### 2.5. PostgreSQL

O backend utiliza PostgreSQL para persistência dos dados.

Download: https://www.postgresql.org/download/

Será necessário ter:
- Servidor PostgreSQL em execução.
- Banco de dados `rescuenet`.
- Usuário com permissão de acesso ao banco.
- Senha configurada para esse usuário.

### 2.6. Mosquitto MQTT Broker

O projeto utiliza MQTT para comunicação entre os nós Android e o backend.

Download: https://mosquitto.org/download/

No Windows, instale o Eclipse Mosquitto e confirme que os executáveis `mosquitto.exe`, `mosquitto_pub.exe` e `mosquitto_sub.exe` estão disponíveis no PATH ou utilize o caminho completo dos executáveis.

---

## 3. Clonando o projeto

Abra o PowerShell e escolha uma pasta para armazenar o projeto.

Clone diretamente a branch de desenvolvimento:

```powershell
git clone --branch feat/sprint-4-robustez --single-branch https://github.com/LeoHazer/rescuenet.git
```

Entre na pasta:

```powershell
cd rescuenet
```

Confira a branch:

```powershell
git branch --show-current
```

O resultado esperado é:

```text
feat/sprint-4-robustez
```

Confira o estado inicial:

```powershell
git status
```

O repositório deve estar sem alterações locais.

---

## 4. Preparando o banco de dados PostgreSQL

O backend exige uma conexão PostgreSQL válida.

A configuração padrão do código utiliza:

| Parâmetro | Valor padrão |
|---|---|
| Host | `127.0.0.1` |
| Porta | `5432` |
| Banco | `rescuenet` |
| Usuário | `rescuenet_app` |
| Senha | Obrigatória |

A senha não possui valor padrão e deve ser configurada no ambiente.

**Atenção:** o servidor Go não executa migrations automaticamente durante a inicialização. O banco precisa estar previamente criado e conter as tabelas esperadas pelo backend, incluindo:

- `teams`
- `locations`
- `node_presence`
- `presence_history`

O script SQL de criação e inicialização do schema deve ser obtido com a equipe responsável pelo banco, caso ainda não esteja disponível no repositório. Não tente iniciar o backend antes de preparar essas estruturas.

Depois do schema base, aplique o script das tabelas de liderança (`team_leaders` e `leader_history`), usadas para exibir o coordenador atual no dashboard. O script é idempotente e concede as permissões ao usuário `rescuenet_app`, se ele existir:

```powershell
psql -h 127.0.0.1 -U postgres -d rescuenet -f backend/migrations/lideranca.sql
```

Aplique também o script da localização por aparelho. Ele adiciona a coluna `node_id` (anulável) à tabela `locations`, cria o índice `(team_id, node_id, recorded_at DESC)` e concede as permissões ao `rescuenet_app`, se ele existir. Também é idempotente:

```powershell
psql -h 127.0.0.1 -U postgres -d rescuenet -f backend/migrations/localizacao_por_no.sql
```

**Atenção:** o backend atual grava `node_id` em toda localização recebida. Sem esse script, as inserções falham com erro de coluna inexistente e as posições deixam de ser salvas.

### Configurando as variáveis de ambiente

No PowerShell, defina as variáveis para a sessão atual:

```powershell
$env:RESCUENET_DB_HOST = "127.0.0.1"
$env:RESCUENET_DB_PORT = "5432"
$env:RESCUENET_DB_NAME = "rescuenet"
$env:RESCUENET_DB_USER = "rescuenet_app"
$env:RESCUENET_DB_PASSWORD = "SUA_SENHA"
```

Substitua `SUA_SENHA` pela senha configurada no PostgreSQL.

Essas variáveis são válidas apenas na janela atual do PowerShell. Caso abra outro terminal para executar o backend, configure-as novamente.

**Nunca publique senhas, tokens ou credenciais no GitHub.**

---

## 5. Inicializando o broker MQTT

O backend espera encontrar o broker MQTT em:

```text
tcp://127.0.0.1:1883
```

O aplicativo Android também utiliza a porta 1883.

Abra um novo terminal PowerShell e execute:

```powershell
mosquitto -v
```

O parâmetro `-v` habilita logs detalhados, úteis para acompanhar conexões, publicações e assinaturas.

Mantenha esse terminal aberto enquanto estiver executando o projeto.

### Testando a comunicação MQTT

Em outro terminal, execute uma assinatura:

```powershell
mosquitto_sub -h 127.0.0.1 -p 1883 -t "rescuenet/#" -v
```

Em um terceiro terminal, publique uma mensagem de teste:

```powershell
mosquitto_pub -h 127.0.0.1 -p 1883 -t "rescuenet/teste" -m "RescueNet MQTT OK"
```

A mensagem publicada deverá aparecer no terminal que está executando o `mosquitto_sub`.

Para encerrar a assinatura, pressione `Ctrl + C`.

---

## 6. Executando o backend Go

Abra outro terminal PowerShell na raiz do projeto.

Entre na pasta do backend:

```powershell
cd backend
```

Baixe as dependências:

```powershell
go mod download
```

Configure novamente as variáveis do PostgreSQL, caso necessário:

```powershell
$env:RESCUENET_DB_HOST = "127.0.0.1"
$env:RESCUENET_DB_PORT = "5432"
$env:RESCUENET_DB_NAME = "rescuenet"
$env:RESCUENET_DB_USER = "rescuenet_app"
$env:RESCUENET_DB_PASSWORD = "SUA_SENHA"
```

Execute o servidor:

```powershell
go run ./cmd/server
```

Quando tudo estiver configurado corretamente, os logs deverão indicar:

- Conexão estabelecida com PostgreSQL.
- Conexão com o broker MQTT.
- Assinatura dos tópicos de localização e presença.
- API REST disponível em `http://127.0.0.1:8080`.

Mantenha esse terminal aberto.

### Endpoints disponíveis

| Método | Endpoint | Finalidade |
|---|---|---|
| GET | `/health` | Verifica o backend e a conexão com o banco |
| GET | `/api/teams` | Lista as equipes |
| GET | `/api/locations/latest` | Última localização de cada equipe |
| GET | `/api/nodes/locations/latest` | Última localização de cada aparelho, por `(team_id, node_id)` |
| GET | `/api/nodes/{node_id}/locations` | Histórico de posições de um aparelho, da mais recente para a mais antiga (`?limit=1..200`, padrão 50) |
| GET | `/api/teams/{id}/locations` | Consulta localizações de uma equipe |
| GET | `/api/teams/{id}/presence` | Consulta presença dos nós |
| GET | `/api/teams/{id}/presence/history` | Consulta histórico de presença |
| GET | `/api/presence` | Presença atual de todos os dispositivos |
| GET | `/api/leaders` | Coordenador atual de cada equipe e se está ativo |
| GET | `/api/leaders/history` | Trocas de coordenador (`?limit=1..200`, padrão 20) |

Em `/api/nodes/locations/latest`, `/api/nodes/{node_id}/locations`, `/api/presence`, `/api/leaders` e `/api/leaders/history`, `node_id` e `epoch_initiator_node_id` são enviados como texto, porque são inteiros de 64 bits e perderiam precisão como número em JavaScript.

A rota `/api/nodes/locations/latest` ignora as localizações sem `node_id` (registros anteriores à migração ou enviados por versões antigas do aplicativo), pois elas não identificam um aparelho. Essas posições continuam disponíveis em `/api/locations/latest`. Exemplo de resposta (dados sintéticos):

```json
[
  {
    "id": 42,
    "team_id": 1,
    "node_id": "4611686018427387905",
    "latitude": -22.5,
    "longitude": -41.9,
    "accuracy": 6.5,
    "battery": 81,
    "recorded_at": "2026-11-25T22:10:00Z"
  }
]
```
 Um coordenador é considerado ativo se anunciou liderança (COORDINATOR ou HEARTBEAT) nos últimos 20 segundos e sua presença não está `offline`.

Exemplo de verificação pelo PowerShell:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health
```

O retorno esperado é semelhante a:

```json
{
  "status": "ok",
  "database": "connected"
}
```

---

## 7. Executando o dashboard

Abra um novo terminal PowerShell.

A partir da raiz do projeto:

```powershell
cd dashboard
```

Instale as dependências:

```powershell
npm install
```

Execute a verificação estática e de tipos:

```powershell
npm run check
```

O resultado esperado é a ausência de erros e avisos.

Inicie o servidor de desenvolvimento:

```powershell
npm run dev
```

O Vite exibirá o endereço local, normalmente:

```text
http://localhost:5173
```

Abra esse endereço no navegador.

O dashboard utiliza proxy para encaminhar as chamadas `/api` e `/health` ao backend em `127.0.0.1:8080`. Portanto, o backend precisa estar em execução para que essas consultas funcionem.

---

## 8. Executando o aplicativo Android

Abra o Android Studio.

1. Selecione **Open**.
2. Escolha a pasta raiz `rescuenet`.
3. Aguarde a sincronização do Gradle.
4. Confira se o Android SDK Platform 37 está instalado.
5. Abra o Device Manager.
6. Inicie um emulador Android.
7. Selecione o emulador na barra superior.
8. Execute o aplicativo pelo botão **Run**.

### Configuração de comunicação com o broker

A configuração atual do aplicativo está em:

```text
app/src/main/java/br/edu/uff/rescuenet/data/mqtt/MqttConfig.kt
```

Os valores utilizados são:

```kotlin
BROKER_HOST = "127.0.0.1"
BROKER_PORT = 1883
TEAM_ID = 1
```

Para permitir que o emulador alcance o broker MQTT executado no computador, utilize o ADB Reverse.

Com o emulador iniciado, execute:

```powershell
adb devices
```

Identifique o dispositivo conectado e execute:

```powershell
adb reverse tcp:1883 tcp:1883
```

Isso encaminha a porta MQTT do emulador para a porta correspondente do computador.

Se o comando `adb` não for reconhecido, utilize o executável `adb.exe` localizado na pasta `platform-tools` do Android SDK ou configure essa pasta no PATH.

**Atenção:** essa configuração é voltada ao ambiente local com emulador. Para executar em um aparelho físico, será necessário adaptar o endereço do broker para um endereço IP acessível na rede local e configurar adequadamente o listener e o firewall do Mosquitto.

### Localização automática

- Ao abrir a tela, o GPS liga sozinho se a permissão de localização já tiver sido concedida. Na primeira execução, toque em **Ativar localização** para conceder a permissão.
- Enquanto houver conexão com o broker e uma posição do GPS, o aplicativo publica a localização automaticamente, no máximo uma vez a cada 10 segundos. Ao (re)conectar, a posição já conhecida é enviada na hora.
- O cartão **Localização** mostra, em texto discreto, o horário do último envio automático.
- O botão **Enviar localização GPS real** continua disponível para um envio imediato.
- Enquanto o rastreamento está ligado, um serviço em primeiro plano do tipo `location` mantém uma notificação ("Compartilhando localização com a central de operações"). Com ele, o envio continua com a tela bloqueada ou com o aplicativo em segundo plano. O serviço para quando o usuário interrompe a localização ou fecha o aplicativo.
- No Android 13 ou superior, o aplicativo também pede a permissão de notificações. Se ela for negada, o serviço funciona do mesmo jeito, só sem a notificação visível.
- No dashboard, a aba **Monitoramento** mostra o rastro das últimas 30 posições de cada aparelho e, para o aparelho selecionado, uma tabela com os últimos envios: horário, intervalo até o envio anterior, deslocamento, precisão e bateria. Intervalos acima de 30 s aparecem destacados como lacuna. A aba **Visão geral** mostra só a última posição de cada aparelho.

O payload publicado inclui o `node_id` do aparelho, para que o dashboard mostre um marcador por aparelho:

```json
{
  "team_id": 1,
  "node_id": 4611686018427387905,
  "latitude": -22.5,
  "longitude": -41.9,
  "accuracy": 6.5,
  "battery": 81,
  "timestamp": 1795650000
}
```

O `node_id` é opcional para o backend: mensagens de versões antigas do aplicativo, sem esse campo, são gravadas com `node_id` nulo. Quando presente, ele precisa ser maior que zero.

---

## 9. Executando os testes automatizados

O projeto possui testes para Android, backend Go e dashboard.

Recomenda-se executar os testes antes de iniciar alterações no código.

### 9.1. Testes unitários Android

Na raiz do projeto, execute:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Esse comando executa os testes unitários JVM do aplicativo.

Para compilar o APK de depuração:

```powershell
.\gradlew.bat :app:assembleDebug
```

O APK será gerado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 9.2. Testes instrumentados Android

Os testes instrumentados precisam de um emulador ou dispositivo Android conectado.

Confira:

```powershell
adb devices
```

Execute:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Esse comando executa os testes diretamente no dispositivo conectado.

### 9.3. Testes do backend Go

Entre na pasta `backend`:

```powershell
cd backend
```

Execute:

```powershell
go test ./...
```

Para compilar o servidor:

```powershell
go build -o "$env:TEMP\rescuenet-backend-validation.exe" ./cmd/server
```

Se não houver erros, a compilação foi concluída com sucesso.

### 9.4. Verificação do dashboard

Entre na pasta `dashboard`:

```powershell
cd dashboard
```

Execute a verificação de tipos:

```powershell
npm run check
```

Execute o build de produção:

```powershell
npm run build
```

Se ambos terminarem sem erros, a validação do dashboard foi concluída.

### 9.5. Testes do simulador de nós

Na raiz do projeto:

```powershell
.\gradlew.bat -p simulator test
```

Os testes colocam vários nós para conversar por um barramento em memória, com relógio simulado, e cobrem eleição inicial, falha do coordenador, entrada de nós, histerese com vários seguidores, bateria baixa e retorno de um nó que caiu.

---

## 10. Testes de integração e funcionamento distribuído

Além dos testes automatizados, o RescueNet possui cenários de validação envolvendo múltiplos nós Android.

Para reproduzir os testes distribuídos:

1. Inicie o PostgreSQL.
2. Inicie o Mosquitto.
3. Inicie o backend Go.
4. Inicie dois emuladores Android.
5. Configure o encaminhamento MQTT para cada emulador.
6. Instale e execute o aplicativo nos dois dispositivos.
7. Acompanhe as mensagens MQTT pelo `mosquitto_sub`.
8. Observe os logs do backend e o estado dos nós.

### Cenários importantes

**A. Descoberta e eleição de coordenador**

Verifique se os nós trocam mensagens de descoberta e eleição e se um coordenador é estabelecido.

**B. Heartbeat**

Após a eleição, acompanhe as mensagens periódicas de heartbeat do coordenador.

**C. Falha do coordenador**

Interrompa o aplicativo que está atuando como coordenador e observe se o nó restante inicia o processo de eleição e assume a coordenação.

**D. Reconexão**

Reabra o aplicativo do nó que foi interrompido e verifique se ele retorna à rede sem provocar uma eleição desnecessária ou uma situação de split-brain.

**E. Presença e LWT**

Verifique a publicação do estado online e a atualização para offline quando ocorrer uma desconexão abrupta.

**F. Histórico de presença**

Consulte os endpoints de presença e histórico para confirmar que os eventos foram recebidos e persistidos no PostgreSQL.

A validação distribuída deve ser feita com dois dispositivos efetivamente conectados ao mesmo broker MQTT. Testes unitários isolados não substituem esse cenário de integração.

### 10.1. Simulador de nós pelo dashboard

O simulador cria nós que participam da eleição como se fossem celulares: cada um tem a própria conexão MQTT, com LWT e presença, e executa **os mesmos arquivos de eleição do aplicativo** (o build do simulador compila `app/src/main/java/br/edu/uff/rescuenet/data/election` diretamente, sem cópia). Assim, os cenários de eleição podem ser demonstrados sem depender de vários aparelhos, e também misturando nós simulados com celulares reais.

Com o broker, o backend e o dashboard em execução, gere e inicie o simulador a partir da raiz do projeto:

```powershell
.\gradlew.bat -p simulator installDist
.\simulator\build\install\rescuenet-simulator\bin\rescuenet-simulator.bat
```

Por padrão, ele usa o broker em `127.0.0.1:1883`, a equipe 1 (a mesma do aplicativo) e expõe a API em `http://127.0.0.1:8090/sim`, acessada pelo dashboard pelo proxy `/sim` do Vite. Para outro broker ou equipe:

```powershell
.\simulator\build\install\rescuenet-simulator\bin\rescuenet-simulator.bat --broker 192.168.0.10:1883 --equipe 1 --porta 8090
```

Encerre com `Ctrl+C`: os nós publicam o LWT `offline` antes de sair.

No dashboard, o painel **Nós simulados** fica na seção "Coordenação da operação", acima da tabela de dispositivos, onde os nós simulados aparecem com a etiqueta "simulado". Cada nó tem um **nível geral**, aplicado a estabilidade, disponibilidade, conectividade, recursos e eficiência de dados, e uma **bateria**. A pontuação resultante é `0,75 × nível + 0,25 × bateria`, e abaixo de 15% de bateria o nó fica inelegível. Os IDs são sequenciais (1001, 1002, ...) e, como no aplicativo, só desempatam pontuações iguais.

| Demonstração | Como fazer no painel | Resultado esperado |
|---|---|---|
| Eleição inicial | Adicionar nós Fraco, Médio e Forte | O de maior pontuação vira coordenador em cerca de 6 s |
| Falha do coordenador | "Derrubar" no coordenador | O LWT marca o nó como offline e outro assume em cerca de 15 s |
| Estabilidade | Adicionar um nó pouco melhor que o líder (menos de 10 pontos) | Ele entra como seguidor e não toma a liderança |
| Histerese | Subir o nível de um seguidor até passar 10 pontos do líder e "Aplicar" | O líder abre a eleição e passa a liderança em cerca de 10 s |
| Bateria baixa | Baixar a bateria do coordenador para menos de 15% e "Aplicar" | Ele fica inelegível e outro nó assume |
| Reconexão | "Reconectar" um nó derrubado | Ele volta como seguidor, sem nova eleição |

Para o cenário de dois coordenadores simultâneos (split-brain), que exige um nó com comportamento incorreto de propósito, use `tools/no_simulado.py` (veja as opções com `python tools/no_simulado.py --help`).

---

## 11. Arquitetura de comunicação

O fluxo geral de comunicação é:

```text
┌──────────────────────┐
│   Aplicativo Android │
│                      │
│ Localização          │
│ Identidade do nó     │
│ Eleição adaptativa   │
│ Presença / Heartbeat │
└──────────┬───────────┘
           │
           │ MQTT
           ▼
┌──────────────────────┐
│    Mosquitto Broker  │
│                      │
│ Tópicos MQTT         │
│ QoS 1                │
│ Retained / LWT       │
└──────────┬───────────┘
           │
           │ MQTT
           ▼
┌──────────────────────┐
│     Backend Go       │
│                      │
│ Recepção de eventos  │
│ API REST             │
│ Persistência         │
└───────┬────────┬─────┘
        │        │
        ▼        ▼
┌────────────┐ ┌────────────────┐
│ PostgreSQL │ │   Dashboard    │
│            │ │                │
│ Localização│ │ Visualização   │
│ Presença   │ │ Monitoramento  │
│ Histórico  │ │                │
└────────────┘ └────────────────┘
```

### Principais tópicos MQTT

| Tópico | Finalidade |
|---|---|
| `rescuenet/teams/{team_id}/location` | Publicação de localização (automática a cada ~10 s, ou pelo botão de envio imediato) |
| `rescuenet/teams/{team_id}/election` | Protocolo de eleição (HELLO, ELECTION, OK, COORDINATOR, HEARTBEAT); o backend registra COORDINATOR e HEARTBEAT para saber o líder atual |
| `rescuenet/teams/{team_id}/nodes/{node_id}/presence` | Estado de presença individual do nó |

---

## 12. Solução de problemas comuns

### Gradle não sincroniza

- Verifique a conexão com a internet.
- Confira se o Android SDK Platform 37 está instalado.
- Confira a versão do JDK selecionada no Android Studio.
- Aguarde o download das dependências.
- Tente executar:

```powershell
.\gradlew.bat --version
```

### Aplicativo não conecta ao MQTT

- Verifique se o Mosquitto está em execução.
- Confira se a porta 1883 está disponível.
- Verifique se o ADB Reverse foi configurado:

```powershell
adb reverse --list
```

- Confira o endereço definido em `MqttConfig.kt`.

### Backend não inicia

Verifique:

- Se o PostgreSQL está em execução.
- Se o banco `rescuenet` existe.
- Se as tabelas necessárias foram criadas.
- Se as variáveis de ambiente estão configuradas.
- Se o Mosquitto está disponível em `127.0.0.1:1883`.

### Dashboard não apresenta dados

- Verifique se o backend está em execução.
- Teste `http://127.0.0.1:8080/health`.
- Verifique o console do navegador.
- Confira se a API está respondendo.
- Verifique se existem dados cadastrados no banco.

### Testes instrumentados não executam

- Inicie um emulador.
- Confira se aparece na saída de `adb devices`.
- Verifique se a imagem do emulador é compatível com o SDK do projeto.

---

## 13. Boas práticas de desenvolvimento

Para manter o repositório organizado:

- Trabalhe em branches específicas para cada funcionalidade.
- Não faça alterações diretamente em `main`.
- Não publique arquivos de configuração local.
- Não publique senhas, tokens ou credenciais.
- Não adicione pastas de build, APKs ou arquivos temporários.
- Execute os testes correspondentes antes de abrir um Pull Request.
- Descreva as alterações realizadas nos commits.
- Atualize a documentação quando modificar a arquitetura ou o processo de execução.

### Atualizando a branch local

Antes de começar uma nova tarefa:

```powershell
git checkout feat/sprint-4-robustez
git pull origin feat/sprint-4-robustez
```

### Criando uma branch para uma nova funcionalidade

Exemplo:

```powershell
git checkout -b feat/nome-da-funcionalidade
```

### Publicando alterações

```powershell
git add .
git commit -m "feat: descricao da funcionalidade"
git push -u origin feat/nome-da-funcionalidade
```

Abra um Pull Request no GitHub para revisão e integração.

---

## 14. Referências técnicas

- Repositório: https://github.com/LeoHazer/rescuenet
- Android Studio: https://developer.android.com/studio
- Android SDK: https://developer.android.com/tools
- Kotlin: https://kotlinlang.org/
- Gradle: https://gradle.org/
- Go: https://go.dev/
- PostgreSQL: https://www.postgresql.org/
- Eclipse Mosquitto: https://mosquitto.org/
- Node.js: https://nodejs.org/
- Svelte: https://svelte.dev/
- Vite: https://vite.dev/

---

## 15. Considerações finais

Este guia foi elaborado para padronizar a preparação do ambiente de desenvolvimento do RescueNet e facilitar a entrada de novos integrantes no projeto.

Seguindo as etapas na ordem apresentada, o desenvolvedor poderá preparar as ferramentas, configurar os serviços, executar os módulos e validar os testes automatizados.

Em caso de dificuldades, recomenda-se conferir primeiro os logs do serviço correspondente e verificar se todas as dependências e configurações foram corretamente aplicadas.

**RescueNet — Sistemas Distribuídos**
