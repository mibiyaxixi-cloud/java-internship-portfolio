#!/usr/bin/env bash
# Optional native server. Download the official v1.15.5 binary yourself into .local/qdrant-bin.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then set -a; source .env; set +a; fi
: "${QDRANT_API_KEY:?Set QDRANT_API_KEY in .env}"
mkdir -p .local/qdrant
export QDRANT__STORAGE__STORAGE_PATH="$PWD/.local/qdrant/storage"
export QDRANT__STORAGE__SNAPSHOTS_PATH="$PWD/.local/qdrant/snapshots"
export QDRANT__SERVICE__HOST=127.0.0.1
export QDRANT__SERVICE__HTTP_PORT=16333
export QDRANT__SERVICE__GRPC_PORT=16334
export QDRANT__SERVICE__API_KEY="$QDRANT_API_KEY"
export QDRANT__TELEMETRY_DISABLED=true
exec .local/qdrant-bin/qdrant
