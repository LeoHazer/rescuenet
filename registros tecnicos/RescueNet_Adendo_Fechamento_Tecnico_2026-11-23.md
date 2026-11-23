# RescueNet — Adendo de Fechamento Técnico

**Data:** 23/11/2026
**Projeto:** Sistemas Distribuídos — RescueNet
**Repositório:** `C:\Users\MESC\AndroidStudioProjects\RescueNet`
**Documento de referência:** `RescueNet_Relatorio_Tecnico_Continuidade_2026-11-19.md`

---

## 1. Objetivo

Este adendo atualiza o estado técnico do RescueNet em 23/11/2026, registrando as implementações, verificações e testes realizados após o relatório de continuidade de 19/11/2026.

O relatório anterior foi preservado sem alterações. Este documento complementa seu histórico, distinguindo resultados efetivamente observados de limitações e validações ainda não realizadas.

## 2. Estado geral do projeto

Na data deste registro, foram verificadas as seguintes partes:

- Aplicativo Android: compilação e testes unitários concluídos com sucesso.
- Eleição adaptativa: implementação integrada ao fluxo MQTT e validada por testes automatizados e experimentos com dois emuladores.
- Backend Go: testes disponíveis e compilação concluídos com sucesso.
- Dashboard: verificação estática e compilação de produção concluídas sem erros.
- Comunicação MQTT: descoberta de participantes, eleição, heartbeat, reconexão e failover exercitados nos cenários descritos neste documento.

Esses resultados não significam que todas as funcionalidades possíveis ou todos os cenários de produção tenham sido validados.

## 3. Aplicativo Android

### 3.1. Verificação de compilação

Comando executado:

`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

Resultado: `BUILD SUCCESSFUL`.

O APK de depuração foi localizado em:

`app\build\outputs\apk\debug\app-debug.apk`

Tamanho registrado: aproximadamente 15,07 MB.

A execução final informou tarefas atualizadas pelo Gradle e reutilização de resultados existentes quando aplicável.

### 3.2. Testes unitários JVM

Foram contabilizados 41 testes unitários:

| Suíte | Testes | Falhas | Erros | Ignorados |
|---|---:|---:|---:|---:|
| AdaptiveBullyElectionTest | 17 | 0 | 0 | 0 |
| AdaptiveBullyPolicyTest | 12 | 0 | 0 | 0 |
| ElectionStateTest | 11 | 0 | 0 | 0 |
| ExampleUnitTest | 1 | 0 | 0 | 0 |
| **Total** | **41** | **0** | **0** | **0** |

Também foram executados anteriormente seis testes instrumentados de serialização e validação das mensagens de eleição, com resultado aprovado.

Os testes JVM e instrumentados são categorias distintas e foram registrados separadamente.

## 4. Eleição adaptativa de coordenador

### 4.1. Critérios de pontuação

Foi implementada uma política de pontuação ponderada:

`S = 0,30E + 0,25B + 0,20D + 0,15C + 0,05R + 0,05F`

Onde:

- E: estabilidade da conexão.
- B: nível de bateria.
- D: disponibilidade operacional.
- C: conectividade atual.
- R: recursos computacionais.
- F: eficiência no uso de dados.

Pesos implementados:

| Critério | Peso |
|---|---:|
| Estabilidade da conexão | 30% |
| Bateria | 25% |
| Disponibilidade | 20% |
| Conectividade | 15% |
| Recursos computacionais | 5% |
| Eficiência de dados | 5% |

A política também contempla:

- Impedimento de elegibilidade para bateria abaixo de 15%.
- Exigência de conectividade MQTT e disponibilidade operacional.
- Margem de histerese de 10 pontos.
- Duas avaliações consecutivas favoráveis para substituição por vantagem de pontuação.
- Desempate pelo identificador do nó (`nodeId`).
- Controle de época da eleição para rejeitar mensagens antigas.

A implementação constitui uma variante adaptativa do algoritmo Bully, com seleção baseada em métricas e mecanismos adicionais de estabilidade.

### 4.2. Descoberta e comunicação

Os participantes anunciam suas informações por mensagens HELLO no tópico:

`rescuenet/teams/1/election`

A eleição utiliza mensagens ELECTION, OK e COORDINATOR. O coordenador envia HEARTBEAT periodicamente.

As mensagens de eleição e heartbeat não são retidas no broker. A descoberta inicial possui uma janela de seis segundos antes do início da eleição, permitindo que os participantes conhecidos sejam considerados antes da escolha inicial.

### 4.3. Testes automatizados

A suíte `AdaptiveBullyElectionTest` contempla 17 cenários, incluindo:

- Eleição inicial.
- Participante de maior prioridade.
- Timeout de eleição.
- Resposta OK.
- Expiração do heartbeat do coordenador.
- Expiração de candidatos.
- Isolamento entre equipes.
- Sincronização de época.
- Substituição de coordenador e histerese.
- Reconhecimento de coordenador por heartbeat após HELLO.
- Rejeição de heartbeat de candidato expirado.

Todos os 17 testes passaram na execução registrada.

## 5. Validação com dois emuladores Android

Foram utilizados dois emuladores:

- Nó A: `6029904383848236762` — emulador `emulator-5554`.
- Nó B: `5365651329210663314` — emulador `emulator-5556`.

### 5.1. Eleição inicial

Foi identificado inicialmente um cenário de divisão de liderança (*split-brain*), no qual os dois participantes podiam assumir a coordenação antes de conhecer o outro.

A causa identificada foi o início imediato da eleição, sem tempo suficiente para descoberta dos participantes.

Foi introduzida uma janela inicial de descoberta de seis segundos.

Após a correção, a captura MQTT mostrou um único coordenador, com heartbeat periódico, enquanto o outro nó permaneceu anunciando sua presença sem assumir simultaneamente a coordenação.

Resultado observado: a eleição inicial apresentou um único coordenador no teste realizado após a correção.

### 5.2. Falha do coordenador e failover

Foi realizado o encerramento forçado do aplicativo que exercia a coordenação.

O nó sobrevivente detectou a ausência de heartbeat e assumiu a coordenação em uma nova época.

Em uma execução anterior, foram observadas diretamente as mensagens ELECTION e COORDINATOR. Na captura posterior à correção de reconexão, foram observadas mensagens HEARTBEAT do novo coordenador na época 4.

Resultado observado: o nó sobrevivente assumiu a coordenação após a falha do anterior.

### 5.3. Reconexão do antigo coordenador

Foi testado o retorno do nó que havia sido encerrado.

O problema identificado anteriormente era que um participante reconectado poderia perder a mensagem COORDINATOR, por ela não ser retida, e ignorar os heartbeats recebidos enquanto estivesse em estado IDLE.

A correção permite que um nó em IDLE reconheça um coordenador previamente descoberto por HELLO, desde que o candidato esteja válido e a época seja aceita.

Após a correção, a captura MQTT mostrou:

- O coordenador ativo continuou enviando HEARTBEAT.
- O nó reconectado voltou a enviar HELLO.
- O nó reconectado não iniciou uma segunda coordenação.
- Não foi observada nova situação de split-brain nessa execução.

Resultado observado: a reconexão do antigo coordenador não provocou uma segunda liderança no teste realizado.

## 6. Backend Go

No diretório `backend`, foram executados:

`go test ./...`

Resultado:

- Pacote MQTT: testes aprovados.
- Pacotes `cmd/server`, `internal/api` e `internal/database`: sem arquivos de teste identificados pelo Go.

Também foi executado:

`go build -o "$env:TEMP\rescuenet-backend-validation.exe" ./cmd/server`

A compilação terminou sem erros. O executável de validação foi direcionado ao diretório temporário do Windows, evitando sobrescrever binários existentes no projeto.

## 7. Dashboard

No diretório `dashboard`, foram executados:

`npm run check`

Resultado: `svelte-check found 0 errors and 0 warnings`, com verificação TypeScript concluída.

Também foi executado:

`npm run build`

Resultado: compilação de produção concluída com sucesso pelo Vite, com 113 módulos transformados.

Os artefatos de produção foram gerados no diretório `dashboard\dist`.

A compilação e a verificação estática não equivalem à validação funcional de todas as telas, indicadores, filtros ou fluxos do dashboard.

## 8. Integração MQTT e presença

As etapas anteriores do projeto registraram a integração entre aplicativo Android, broker MQTT, backend Go, PostgreSQL e API.

A Sprint 5 também incluiu identidade persistente dos nós e presença MQTT com mensagens online/offline, incluindo Last Will and Testament (LWT), além de persistência de histórico de presença.

Os testes anteriores demonstraram a atualização do estado de presença após encerramento forçado do aplicativo.

A existência dessas funcionalidades no backend não deve ser confundida com a comprovação de que todos os elementos de presença e alertas estejam integrados visualmente ao dashboard.

## 9. Limitações técnicas e ressalvas

Para manter a apresentação e a documentação fiéis ao que foi implementado, devem ser consideradas as seguintes limitações:

1. **Recursos computacionais:** a avaliação utiliza disponibilidade de memória como indicador; não há medição completa de CPU implementada.
2. **Eficiência de dados:** o critério existe na política, mas seu valor deve ser tratado como indicador da implementação, não como uma medição abrangente de eficiência de rede em ambiente de produção.
3. **Conectividade remota:** a elegibilidade de participantes remotos utiliza as informações disponíveis nas mensagens recebidas; não representa uma telemetria completa e continuamente atualizada de todos os recursos de cada dispositivo.
4. **Validação de histerese:** a lógica de substituição por vantagem persistente foi coberta por testes automatizados. Não foi registrada uma demonstração física completa de troca de liderança motivada exclusivamente por queda de desempenho.
5. **Ambiente de teste:** os experimentos distribuídos foram realizados com dois emuladores Android e broker MQTT local, não com uma frota de dispositivos físicos em uma rede de produção.
6. **Localização:** coordenadas usadas em testes anteriores foram simuladas e não devem ser apresentadas como posições GPS reais.
7. **Dashboard:** compilação sem erros não comprova, isoladamente, o funcionamento integral de todos os recursos visuais e operacionais.

## 10. Backups e rastreabilidade

Foram preservados backups das etapas de implementação, incluindo identidade dos nós, presença, integração MQTT, eleição, descoberta, histerese, sincronização de época e correção de reconexão do heartbeat.

O relatório de continuidade de 19/11/2026 foi copiado para:

`backups\relatorio-fechamento-20261123\RescueNet_Relatorio_Tecnico_Continuidade_2026-11-19.md`

A cópia foi verificada por SHA-256 e apresentou o mesmo hash do arquivo original:

`8A8CE2BF228F85C44A44109827BCF2C1329888C3012F18B5B8A10A59FFFE51A1`

O documento original permanece preservado.

## 11. Conclusão do fechamento técnico

Na data de 23/11/2026, o RescueNet apresenta implementação integrada de eleição adaptativa de coordenador, comunicação MQTT, heartbeat, detecção de falha e recuperação após reconexão, com testes automatizados aprovados e experimentos distribuídos realizados em dois emuladores.

As verificações finais de compilação do Android, backend Go e dashboard foram concluídas sem erros.

O sistema demonstrou, no ambiente de testes utilizado, capacidade de eleger um coordenador, detectar sua ausência, promover um participante sobrevivente e permitir a reconexão do nó anterior sem duplicação de liderança no cenário validado.

As limitações descritas neste adendo devem ser consideradas na apresentação acadêmica, especialmente a diferença entre testes automatizados, experimentos em emuladores e validação em ambiente real.

**Status registrado:** fechamento técnico das implementações e verificações realizadas até 23/11/2026, sem afirmar validação de funcionalidades ou cenários não executados.