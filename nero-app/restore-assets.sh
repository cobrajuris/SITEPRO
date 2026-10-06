#!/usr/bin/env bash
# Recria as imagens (ícone e logo) a partir de binary-assets.b64.
set -euo pipefail
cd "$(dirname "$0")"
while IFS=$'\t' read -r path data; do
  mkdir -p "$(dirname "$path")"
  printf '%s' "$data" | base64 -d > "$path"
done < binary-assets.b64
echo "Assets restaurados."
