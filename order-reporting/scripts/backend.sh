#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi
# 可写到项目内部，避免污染全局 Maven 缓存；Wrapper 仍使用官方构建发行包。
export MAVEN_USER_HOME="${MAVEN_USER_HOME:-$ROOT/.local/maven}"
mkdir -p "$MAVEN_USER_HOME"
case "${1:-run}" in
  run) exec ./mvnw -B -ntp -Dmaven.repo.local="$MAVEN_USER_HOME/repository" -pl backend spring-boot:run ;;
  test) exec ./mvnw -B -ntp -Dmaven.repo.local="$MAVEN_USER_HOME/repository" test ;;
  verify) exec ./mvnw -B -ntp -Dmaven.repo.local="$MAVEN_USER_HOME/repository" -Pmysql-it verify ;;
  package) exec ./mvnw -B -ntp -Dmaven.repo.local="$MAVEN_USER_HOME/repository" package ;;
  debug) exec ./mvnw -B -ntp -Dmaven.repo.local="$MAVEN_USER_HOME/repository" -pl backend spring-boot:run '-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005' ;;
  *) echo 'Usage: scripts/backend.sh [run|test|verify|package|debug]' >&2; exit 1 ;;
esac
