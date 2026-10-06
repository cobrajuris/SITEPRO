#!/usr/bin/env bash
# Teste real da Ilha Dinâmica num emulador Android (com câmera furada simulada).
# Falha (exit 1) se o app fechar, travar ou registrar erro de JavaScript.
set -u
APK="$1"
OUT="$2"
PKG=com.enzo.ilhadinamica
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
FAIL=0
fail() { echo "::error::$*"; FAIL=1; }
step() { echo; echo "=== $* ==="; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
cmdc() { adb shell am start-foreground-service -n "$PKG/.IslandService" -a com.enzo.ilhadinamica.CMD --es c "$1" >/dev/null; }
apppid() { adb shell pidof "$PKG" | tr -d '\r'; }

step "Preparando o emulador"
adb wait-for-device
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell settings put system user_rotation 0
adb shell settings put system accelerometer_rotation 0
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.hole || true
sleep 4
adb shell wm size
adb shell dumpsys window displays | grep -i -m2 cutout || true

step "Instalando"
adb install -r -g "$APK" || { fail "instalação falhou"; exit 1; }
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener "$PKG/$PKG.NotifListener" || true
adb logcat -c

step "Abrindo o painel"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 5
shot 01-painel

step "Ligando a ilha"
adb shell am start-foreground-service -n "$PKG/.IslandService" >/dev/null
sleep 6
PID0="$(apppid)"
echo "pid do app: $PID0"
[ -n "$PID0" ] || fail "app não está rodando depois de ligar a ilha"
adb shell input keyevent KEYCODE_HOME
sleep 3
shot 02-bolha-tela-inicial

step "Passando por todas as abas"
for t in music timer bright assist cal ig wa tg tt call yt ifood maps file; do
  cmdc "tab:$t"
  sleep 2.5
  shot "tab-$t"
done
cmdc close
sleep 2
shot 03-recolhida

step "Tocando na bolha (abre) e fora (fecha)"
SIZE="$(adb shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
W="${SIZE%% *}"
CUT="$(adb shell dumpsys window | tr -d '\r' | grep -o 'DisplayCutout{insets=Rect(0, [0-9]*' | head -1 | grep -o '[0-9]*$')"
[ -n "$CUT" ] || CUT=80
BX=$((W / 2)); BY=$((CUT / 2))
echo "tocando a bolha em $BX,$BY"
adb shell input tap "$BX" "$BY"
sleep 2
shot 04-toque-abriu
adb shell input tap "$BX" 1500
sleep 2
shot 05-toque-fora-fechou

step "Mensagem de grupo do WhatsApp com foto (notificação real pelo leitor)"
cmdc "shelltest:on"
AV="$(python3 "$HERE/avatar.py")"
adb shell "cmd notification post -S messaging --conversation 'Família Meneses' --message 'Ana:Chegando em 10 minutos, separa a mesa!' -I data:base64,$AV familia 'Nova mensagem'" || fail "não consegui postar a notificação de teste"
sleep 3
shot 06-whatsapp-grupo-com-foto
adb shell "cmd notification post -I data:base64,$AV -t 'Carlos' contato 'Oi, já enviei o arquivo pra você.'" || true
sleep 3
shot 07-whatsapp-contato-com-foto
cmdc "shelltest:off"

step "Estresse: comandos seguidos"
for i in $(seq 1 25); do cmdc toggle; done
for i in $(seq 1 8); do adb shell am start-foreground-service -n "$PKG/.IslandService" >/dev/null; done
for i in $(seq 1 3); do adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 1; adb shell input keyevent KEYCODE_HOME; done
sleep 12

step "Matando o renderizador do WebView (situação de pouca memória)"
for p in $(adb shell ps -A -o PID,NAME | tr -d '\r' | awk '/sandboxed_process|webview_service|:sandboxed/ {print $1}'); do
  echo "matando renderizador $p"; adb shell kill -9 "$p" || true
done
sleep 6
cmdc "tab:music"
sleep 3
shot 08-depois-do-renderizador-morrer

step "Girando a tela"
adb shell settings put system user_rotation 1
sleep 4
shot 09-paisagem
adb shell settings put system user_rotation 0
sleep 4
cmdc "tab:wa"
sleep 3
shot 10-retrato-de-novo

step "Verificações finais"
PID1="$(apppid)"
echo "pid inicial=$PID0 final=$PID1"
[ -n "$PID1" ] || fail "o app FECHOU durante o teste"
[ "$PID0" = "$PID1" ] || fail "o app reiniciou (pid mudou de $PID0 para $PID1)"
adb shell dumpsys activity services "$PKG" | grep -q IslandService || fail "o serviço da ilha não está mais rodando"
adb shell dumpsys window windows | grep -q "Ilha" || fail "a janela da ilha sumiu"

adb logcat -d -b crash > "$OUT/crash.txt" || true
adb logcat -d > "$OUT/logcat.txt" || true
if grep -q "$PKG" "$OUT/crash.txt"; then
  fail "o app teve um crash:"
  cat "$OUT/crash.txt"
fi
grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" | grep -i -B2 -A20 "$PKG" && fail "erro fatal no logcat"
echo "--- logs da ilha ---"
grep -E " (Ilha|IlhaJS|IlhaNotif)\s*:" "$OUT/logcat.txt" | tail -60 || true
if grep -q "IlhaJS" "$OUT/logcat.txt"; then fail "erro de JavaScript na ilha"; fi

if [ "$FAIL" = "0" ]; then echo "TUDO CERTO: nenhum crash, nenhum erro."; fi
exit "$FAIL"
