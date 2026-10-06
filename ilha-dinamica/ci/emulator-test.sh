#!/usr/bin/env bash
# Teste real da Ilha Dinâmica num emulador Android (com câmera furada simulada).
# Falha (exit 1) se o app fechar, travar, não abrir no toque ou registrar erro de JavaScript.
set -u
APK="$1"
OUT="$2"
PKG=com.enzo.ilhadinamica
A11Y="$PKG/$PKG.IslandA11y"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
FAIL=0
fail() { echo "::error::$*"; FAIL=1; }
step() { echo; echo "=== $* ==="; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
cmdc() { adb shell am broadcast -n "$PKG/.CmdReceiver" --es c "$1" >/dev/null; }
apppid() { adb shell pidof "$PKG" | tr -d '\r'; }
# Opacidade real da janela da cápsula no sistema (1.0 = aberta, 0.0 = fechada).
capalpha() { adb shell dumpsys window windows | tr -d '\r' | grep -A4 "psula}" | grep -o "alpha=[0-9.]*" | head -1 | cut -d= -f2; }
bubblepos() {
  local pos; pos="$(adb logcat -d -s Ilha:I | grep -o 'bolha em x=[0-9]* y=[0-9]*' | tail -1)"
  BX="$(echo "$pos" | sed -n 's/.*x=\([0-9]*\).*/\1/p')"; BY="$(echo "$pos" | sed -n 's/.*y=\([0-9]*\).*/\1/p')"
  echo "bolha: $pos"
}

step "Preparando o emulador"
adb wait-for-device
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell settings put system user_rotation 0
adb shell settings put system accelerometer_rotation 0
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.hole || true
sleep 4
adb shell wm size

step "Instalando"
adb install -r -g "$APK" || { fail "instalação falhou"; exit 1; }
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener "$PKG/$PKG.NotifListener" || true
adb logcat -c

step "Abrindo o painel"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 5
shot 01-painel

step "Ativando a acessibilidade (modo de fábrica) e ligando a ilha"
adb shell settings put secure enabled_accessibility_services "$A11Y"
adb shell settings put secure accessibility_enabled 1
sleep 4
cmdc enable
sleep 6
adb logcat -d -s Ilha:I | grep -q "acessibilidade conectada" || fail "o serviço de acessibilidade não conectou"
PID0="$(apppid)"
echo "pid do app: $PID0"
[ -n "$PID0" ] || fail "app não está rodando depois de ligar a ilha"
adb shell input keyevent KEYCODE_HOME
sleep 3
shot 02-bolha-tela-inicial
bubblepos
[ -n "$BX" ] || { fail "posição da bolha não informada"; BX=540; BY=60; }

step "Tocando na bolha (deve abrir) e fora (deve fechar)"
adb shell input tap "$BX" "$BY"
sleep 2
shot 03-toque-na-bolha-abriu
A="$(capalpha)"; echo "opacidade da cápsula depois do toque: $A"
[ "$A" = "1.0" ] || fail "tocar na bolha NÃO abriu a cápsula (alpha=$A)"
adb shell input tap 540 1700
sleep 2
shot 04-toque-fora-fechou
A="$(capalpha)"; echo "opacidade depois do toque fora: $A"
[ "$A" = "0.0" ] || fail "tocar fora NÃO fechou a cápsula (alpha=$A)"
adb shell input tap "$BX" "$BY"; sleep 1.5
adb shell input tap "$BX" "$BY"; sleep 1.5
A="$(capalpha)"; [ "$A" = "0.0" ] || fail "tocar duas vezes na bolha deveria abrir e fechar (alpha=$A)"

step "Passando por todas as abas"
for t in music timer bright assist cal ig wa tg tt call yt ifood maps file; do
  cmdc "tab:$t"
  sleep 2.5
  shot "tab-$t"
done
cmdc close
sleep 2

step "Mensagem de grupo do WhatsApp com foto (notificação real pelo leitor)"
cmdc "shelltest:on"
AV="$(python3 "$HERE/avatar.py")"
adb shell "su 2000 cmd notification post -S messaging --conversation 'Família Meneses' --message 'Ana:Chegando em 10 minutos, separa a mesa!' -I data:base64,$AV familia 'Nova mensagem'" || fail "não consegui postar a notificação de teste"
sleep 3
shot 05-whatsapp-grupo-com-foto
adb logcat -d -s IlhaNotif:I | grep -q "aba=wa foto=true" || fail "a mensagem do grupo com foto NÃO apareceu na ilha"
A="$(capalpha)"; [ "$A" = "1.0" ] || fail "a notificação não abriu a cápsula (alpha=$A)"
adb shell "su 2000 cmd notification post -I data:base64,$AV -t 'Carlos' contato 'Bora jogar hoje à noite?'" || true
sleep 3
shot 06-whatsapp-contato-com-foto
cmdc "shelltest:off"
sleep 8
A="$(capalpha)"; echo "opacidade 8 s depois da mensagem: $A (deve recolher sozinha)"
[ "$A" = "0.0" ] || fail "a cápsula da mensagem não recolheu sozinha"

step "Estresse: comandos e toques seguidos"
for i in $(seq 1 25); do cmdc toggle; done
for i in $(seq 1 10); do adb shell input tap "$BX" "$BY"; done
for i in $(seq 1 3); do adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 1; adb shell input keyevent KEYCODE_HOME; done
sleep 8

step "Matando o renderizador do WebView (situação de pouca memória)"
for p in $(adb shell ps -A -o PID,NAME | tr -d '\r' | awk '/sandboxed_process/ {print $1}'); do
  echo "matando renderizador $p"; adb shell kill -9 "$p" || true
done
sleep 6
cmdc "tab:music"
sleep 3
shot 07-depois-do-renderizador-morrer
adb shell input tap "$BX" "$BY"; sleep 2
A="$(capalpha)"; [ "$A" = "0.0" ] || [ "$A" = "1.0" ] || fail "a cápsula não voltou depois do renderizador morrer"

step "Girando a tela"
adb shell settings put system user_rotation 1
sleep 4
shot 08-paisagem
adb shell settings put system user_rotation 0
sleep 4
cmdc "tab:wa"
sleep 3
shot 09-retrato-de-novo

step "Modo alternativo (sem acessibilidade)"
adb shell settings put secure enabled_accessibility_services '""'
adb shell settings put secure accessibility_enabled 0
sleep 8
adb shell dumpsys activity services "$PKG" | grep -q "IslandService" || fail "o modo alternativo não assumiu a ilha ao desligar a acessibilidade"
cmdc "tab:yt"
sleep 3
shot 10-modo-alternativo

step "Verificações finais"
PID1="$(apppid)"
echo "pid inicial=$PID0 final=$PID1"
[ -n "$PID1" ] || fail "o app FECHOU durante o teste"
[ "$PID0" = "$PID1" ] || fail "o app reiniciou (pid mudou de $PID0 para $PID1)"
adb shell dumpsys window windows | grep -q "psula}" || fail "a janela da ilha sumiu"

adb logcat -d -b crash > "$OUT/crash.txt" || true
adb logcat -d > "$OUT/logcat.txt" || true
if grep -q "$PKG" "$OUT/crash.txt"; then
  fail "o app teve um crash:"
  cat "$OUT/crash.txt"
fi
grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" | grep -i -B2 -A20 "$PKG" && fail "erro fatal no logcat"
echo "--- logs da ilha ---"
grep -E " (Ilha|IlhaJS|IlhaNotif)\s*:" "$OUT/logcat.txt" | grep -v "notificação de" | tail -40 || true
if grep -q "IlhaJS" "$OUT/logcat.txt"; then fail "erro de JavaScript na ilha"; fi

if [ "$FAIL" = "0" ]; then echo "TUDO CERTO: nenhum crash, nenhum erro."; fi
exit "$FAIL"
