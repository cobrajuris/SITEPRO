# Ilha Dinâmica (Bolha da câmera)

App Android que coloca uma ilha dinâmica em volta do furo da câmera frontal, seguindo o protótipo
"Bolha flutuante Android":

- **Bolha** — círculo fixo em volta da câmera, com anel de progresso na cor da aba atual.
- **Cápsula** — abre logo abaixo da câmera ao tocar na bolha: círculo à esquerda, roda de itens no meio
  e roda numérica à direita.
- **14 abas** — Música, Timer, Brilho, Assistente, Calendário, Instagram, WhatsApp, Telegram, TikTok,
  Chamada, YouTube, iFood, Maps e Enviar arquivo.
- **Gestos** — os tracinhos (ou deslizar para os lados) trocam de aba; deslizar para cima ou tocar fora recolhe.

## Integrações reais

| Aba | O que é real |
| --- | --- |
| Música / YouTube | Título, artista, capa, tocar/pausar, anterior/próxima (sessão de mídia do player) |
| Música → Vol | Volume de mídia do aparelho |
| Brilho | Brilho e modo automático (com a permissão "Alterar brilho") |
| Timer | Contagem regressiva com vibração e alarme no fim |
| Assistente | Lembrete que "liga" com toque e vibração |
| Calendário | Eventos da semana (com a permissão de calendário) |
| WhatsApp / Instagram / Telegram / TikTok / iFood / Maps / Chamada | Notificações reais abrem a cápsula com nome e mensagem |

## Instalar

Baixe o `IlhaDinamica.apk` na página **Releases** do repositório (gerado pelo GitHub Actions),
instale e, no app, toque em **Ligar a ilha** e conceda "Aparecer sobre outros apps" e "Acesso às notificações".

## Compilar

```bash
cd ilha-dinamica
./gradlew assembleRelease
```
