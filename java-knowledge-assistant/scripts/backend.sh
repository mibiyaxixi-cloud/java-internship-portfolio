#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then set -a; source .env; set +a; fi
export MAVEN_USER_HOME="$PWD/.local/maven"
mkdir -p .local/repository
case "${1:-run}" in
  run) exec ./mvnw -Dmaven.repo.local="$PWD/.local/repository" spring-boot:run ;;
  test) exec ./mvnw -Dmaven.repo.local="$PWD/.local/repository" test ;;
  verify) exec ./mvnw -Dmaven.repo.local="$PWD/.local/repository" -Pintegration verify ;;
  package) exec ./mvnw -Dmaven.repo.local="$PWD/.local/repository" package ;;
  *) echo 'Usage: scripts/backend.sh [run|test|verify|package]' >&2; exit 1 ;;
esac
