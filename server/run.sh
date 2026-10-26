#!/usr/bin/env sh
# 裸跑（不装 Docker）。数据和配置都在 ./data 里，备份这个目录就够了。
set -eu
cd "$(dirname "$0")"
exec python3 serve.py \
  --host "${YG_HOST:-0.0.0.0}" \
  --port "${YG_PORT:-8787}" \
  --data-dir "${YG_DATA_DIR:-./data}"
