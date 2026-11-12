#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ -f .env ]]; then set -a; source .env; set +a; fi
export MAVEN_USER_HOME="${MAVEN_USER_HOME:-$ROOT/.local/maven}"
# Explicit opt-in: the probe is not named *Test or *IT and is excluded from normal verification.
OUTPUT="$ROOT/.local/benchmark/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$(dirname "$OUTPUT")"
./mvnw -B -ntp -Dmaven.repo.local="$MAVEN_USER_HOME/repository" -Pmysql-it \
  -Dit.test=BenchmarkMySqlProbe -Dbenchmark.output="$OUTPUT" verify
echo "Benchmark evidence: $OUTPUT/result.json"
