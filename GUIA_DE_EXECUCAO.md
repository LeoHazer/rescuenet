# Guia de execução do RescueNet

Como rodar o sistema completo em um computador com Windows que acabou de clonar o repositório. Todos os comandos são para o PowerShell.

## 1. Instalar as ferramentas (uma vez)

```powershell
winget install --id Git.Git -e
winget install --id Docker.DockerDesktop -e
winget install --id GoLang.Go -e
winget install --id OpenJS.NodeJS.LTS -e
winget install --id EclipseAdoptium.Temurin.21.JDK -e
```

Depois, **feche e abra o PowerShell** e abra o **Docker Desktop** (na primeira vez, aceite os termos; se ele pedir para instalar o WSL, aceite e reinicie o computador).

## 2. Clonar

```powershell
git clone https://github.com/LeoHazer/rescuenet.git
cd rescuenet
git checkout feat/sprint-4-robustez
```

## 3. Rodar

Com o Docker Desktop aberto, na pasta do projeto:

```powershell
docker compose up -d
```

Sobe o broker MQTT e o banco PostgreSQL. Na primeira vez, também cria as tabelas. Depois disso, eles voltam sozinhos sempre que o Docker Desktop abrir, então este comando só é necessário uma vez.

Abra três terminais na pasta do projeto e deixe cada um rodando:

**Terminal 1: backend**

```powershell
cd backend
go run ./cmd/server
```

**Terminal 2: dashboard**

```powershell
cd dashboard
npm install
npm run dev
```

O `npm install` só é necessário na primeira vez. Depois, abra **http://localhost:5173** no navegador.

**Terminal 3: simulador de nós (opcional)**

```powershell
.\gradlew.bat -p simulator run
```

Na primeira vez, o Gradle baixa as dependências e compila, o que leva alguns minutos. Quando aparecer `API: http://127.0.0.1:8090/sim/nodes`, o painel **Nós simulados** do dashboard passa a funcionar.

Para encerrar, use **Ctrl+C** em cada terminal.

## 4. Celulares

**Uma única vez**, libere a porta do broker no firewall. Abra o PowerShell **como administrador** e rode:

```powershell
New-NetFirewallRule -DisplayName "RescueNet MQTT" -Direction Inbound -Protocol TCP -LocalPort 1883 -Action Allow
```

Descubra o IP do computador com `ipconfig` (linha **Endereço IPv4** do Wi-Fi ou da rede cabeada, por exemplo `192.168.0.10`).

Em cada celular:

1. Instale o `app-debug.apk` (pode receber de um colega pelo WhatsApp ou pelo Drive; o Android vai pedir para permitir apps de fontes desconhecidas). Se já houver uma versão antiga do app que foi gerada em outro computador, desinstale-a antes.
2. Conecte o celular ao **mesmo Wi-Fi** do computador.
3. Abra o app, aceite as permissões e, em **Endereço do broker**, digite o IP do computador.
4. Toque em **Conectar ao broker**. Em uns 10 segundos, o celular aparece no dashboard.

Se a rede da faculdade não deixar os aparelhos se enxergarem, crie um hotspot no celular de alguém e conecte o computador e os outros celulares nele.

## 5. Demonstração sem celulares

No dashboard, na seção **Coordenação da operação**, o painel **Nós simulados** cria nós que participam da eleição como se fossem celulares:

| Para mostrar | Faça | O que acontece |
|---|---|---|
| Eleição | Adicione nós **Fraco**, **Médio** e **Forte** | O Forte vira coordenador em ~6 s |
| Queda do líder | **Derrubar** no coordenador | Outro nó assume em ~15 s |
| Estabilidade | Adicione um nó pouco melhor que o líder | Ele entra como seguidor |
| Histerese | Aumente o nível de um seguidor até passar 10 pontos do líder e clique em **Aplicar** | Ele vira coordenador em ~10 s |
| Bateria baixa | Coloque bateria abaixo de 15 no coordenador e clique em **Aplicar** | Um nó com bateria assume |
| Nó único sem bateria | **Remover todos** e adicione um nó com bateria 10 | Ele lidera, pois não há alternativa |

Os celulares e os nós simulados participam da mesma eleição.

## Se algo der errado

| Problema | O que fazer |
|---|---|
| `docker compose` dá erro de conexão | Abra o Docker Desktop, espere ele iniciar e rode de novo |
| `go`, `npm` ou `java` não são reconhecidos | Feche e abra o terminal depois de instalar (ou reinicie o computador) |
| O celular fica em "Sem conexão... Tentando reconectar" | Confira o IP digitado, se está no mesmo Wi-Fi e se a regra do firewall foi criada. Toque em **Cancelar conexão**, corrija e conecte de novo |
| O dashboard mostra "Desconectado" | O backend (terminal 1) não está rodando |
| O painel de nós simulados diz que o simulador está desligado | O simulador (terminal 3) não está rodando ou ainda está compilando |
| Quero apagar todos os dados e começar do zero | `docker compose down -v` e depois `docker compose up -d` |

## Compilar o APK (só para quem for gerar o app)

Instale o Android Studio (`winget install --id Google.AndroidStudio -e`), abra a pasta do projeto nele, instale a plataforma **Android API 37** em **Tools > SDK Manager** e rode:

```powershell
.\gradlew.bat :app:assembleDebug
```

O APK fica em `app\build\outputs\apk\debug\app-debug.apk`. Se aparecer um erro de "Configuration cache", rode o mesmo comando com `--no-configuration-cache` no final.
