# Nero — assistente pessoal (Android)

App Android nativo (Kotlin, componentes nativos do Android) com a identidade do gato Nero, usando o OpenRouter como cérebro.

## Recursos
- Chat com respostas em tempo real (streaming) e botão de parar
- Qualquer modelo do OpenRouter: grátis (`openrouter/free`, padrão), automático (`openrouter/auto`) ou um ID digitado, com o grátis como reserva
- **Modo profundo**: pede raciocínio alto ao modelo antes de responder
- Histórico de conversas salvo no aparelho, com menu lateral
- Copiar e refazer respostas, blocos de código destacados
- Sugestões rápidas na tela inicial, splash animada com a logo
- Ícone adaptativo gerado a partir da logo

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
gradle assembleRelease
```
