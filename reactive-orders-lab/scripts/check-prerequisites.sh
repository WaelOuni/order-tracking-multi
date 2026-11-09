#!/usr/bin/env bash
# Diagnostic explicite : ne prétend pas exécuter Testcontainers sans Docker.
set -euo pipefail
for executable in java node npm docker; do
  if ! command -v "$executable" >/dev/null 2>&1; then
    printf 'Outil manquant : %s\n' "$executable" >&2
    exit 1
  fi
done
java -version
node --version
npm --version
docker compose version
docker info --format 'Docker serveur : {{.ServerVersion}}'
printf 'Prérequis complets disponibles. Java 21 est requis pour ce projet.\n'
