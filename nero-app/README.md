# Nero — assistente pessoal (Android)

App Android nativo (Kotlin, componentes nativos do Android) com a identidade do gato Nero, usando o OpenRouter como cérebro.

## Recursos
- Chat com respostas em tempo real (streaming) e botão de parar
- Qualquer modelo do OpenRouter: grátis (`openrouter/free`, padrão), automático (`openrouter/auto`) ou um ID digitado, com o grátis como reserva
- **Modo profundo**: pede raciocínio alto ao modelo antes de responder
- Personalidade editável (system prompt) em Ajustes
- **Voz**: botão de microfone (reconhecimento de voz do Android, pt-BR)
- **Imagem**: anexar foto da galeria ou da câmera; o modelo com visão lê textos e entende a imagem
- **Lembretes**: peça "me lembra amanhã às 15h de…" (ou mande a foto de um convite) e o Nero cria o
  lembrete, avisa com notificação no horário e permite adicionar ao Google Agenda
- Chave conferida no OpenRouter antes de salvar e guardada criptografada (Android Keystore)
- Histórico de conversas salvo no aparelho, com menu lateral
- Copiar e refazer respostas, blocos de código destacados
- Sugestões rápidas na tela inicial, splash animada com a logo
- Ícone adaptativo gerado a partir da logo
- Visual de vidro fosco com fundo orgânico animado, degradê água → pêssego → lilás e
  tipografia Urbanist + Doto (matriz de pontos), inspirado nas referências Notis+/MyNotes

## Como obter o APK
Cada push que altera `nero-app/` dispara o workflow **Nero APK** no GitHub Actions.
Abra a execução em *Actions → Nero APK* e baixe o artefato `Nero-apk`.

## Primeiro uso
Abra **Ajustes**, cole sua chave do OpenRouter (openrouter.ai/keys) e salve. O modelo grátis funciona sem cartão.
A chave fica só no aparelho.

## Compilar localmente
Requer Android SDK, JDK 17 e Gradle 8.14. As imagens ficam em texto (`binary-assets.b64`);
recrie-as antes de compilar:

```
cd nero-app
./restore-assets.sh
./fetch-fonts.sh
gradle assembleRelease
```
