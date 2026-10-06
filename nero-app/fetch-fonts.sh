#!/usr/bin/env bash
# Baixa as fontes Urbanist e Doto (licença OFL, pacotes @fontsource do npm) e converte para TTF.
set -euo pipefail
cd "$(dirname "$0")"
out=app/src/main/res/font
tmp=$(mktemp -d)
mkdir -p "$out"
for p in urbanist doto; do
  curl -sSL -o "$tmp/$p.tgz" "https://registry.npmjs.org/@fontsource/$p/-/$p-5.3.0.tgz"
  mkdir -p "$tmp/$p" && tar xzf "$tmp/$p.tgz" -C "$tmp/$p"
done
python3 -m pip install -q fonttools
python3 - "$tmp" "$out" <<'PY'
import sys
from fontTools.ttLib import TTFont
tmp, out = sys.argv[1], sys.argv[2]
for src, dst in [
    ("urbanist/package/files/urbanist-latin-300-normal.woff", "urbanist_light.ttf"),
    ("urbanist/package/files/urbanist-latin-400-normal.woff", "urbanist_regular.ttf"),
    ("urbanist/package/files/urbanist-latin-600-normal.woff", "urbanist_semibold.ttf"),
    ("doto/package/files/doto-latin-700-normal.woff", "doto_bold.ttf"),
]:
    font = TTFont(f"{tmp}/{src}")
    font.flavor = None
    font.save(f"{out}/{dst}")
PY
echo "Fontes prontas em $out"
