# Sprint 0 — Ambiente e repositório

**Concluída em:** 31/10/2026

## O que foi feito
- Ambiente: Git, Go, Node, Android Studio (sem emulador; testes no Moto G50 via USB)
- Repositório com `main` e `develop`, templates de PR/Issue, `.gitignore`, `.gitattributes`, `.editorconfig`
- Projeto Android (Compose) em `apps/android`, com minSdk 26
- CI Android no GitHub Actions, com APK de debug como artefato
- Logo em `brand/logo/`

## Decisões
- **minSdk 26:** serviços em primeiro plano estáveis para o GPS; o Moto (Android 11) roda com folga
- **Infra em nuvem (HiveMQ Cloud + Supabase) em vez de Docker local:** notebook com 8 GB de RAM
- **JDK 25 no CI:** exigido pelo `gradle-daemon-jvm.properties` gerado pelo Android Studio

## Dificuldades
- Aviso de caminho com caracteres não-ASCII no Android Studio
- CI falhou por incompatibilidade de JDK (workflow com 17, projeto exigindo 25)
- Bloco de comandos colado no PowerShell saiu embaralhado; arquivos criados pelo editor são mais seguros

## Próximo
Contrato de mensagens MQTT e esquema do banco.