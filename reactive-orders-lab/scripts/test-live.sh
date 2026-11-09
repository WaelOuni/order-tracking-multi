#!/usr/bin/env bash
# E2E sur une VRAIE stack distincte du projet de développement.
# Les volumes supprimés au nettoyage appartiennent uniquement à ce projet E2E.
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"
docker compose version >/dev/null
docker info >/dev/null

# Ports dédiés afin de pouvoir conserver le laboratoire de développement ouvert.
export APP_PORT="${E2E_APP_PORT:-18080}"
export MONGO_PORT="${E2E_MONGO_PORT:-37017}"
export KAFKA_PORT="${E2E_KAFKA_PORT:-19092}"
export CATALOG_PORT="${E2E_CATALOG_PORT:-18089}"
E2E_PROJECT="reactive-orders-lab-e2e-$$"

cleanup() {
  result=$?
  trap - EXIT
  if [[ "$result" -ne 0 ]]; then
    docker compose -p "$E2E_PROJECT" logs --no-color --tail=200 || true
  fi
  # Nom généré par ce script : aucune donnée du projet de développement touchée.
  docker compose -p "$E2E_PROJECT" down --volumes --remove-orphans || true
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

npm ci
npx playwright install chromium
docker compose -p "$E2E_PROJECT" up -d --build --wait --wait-timeout 240
PLAYWRIGHT_LIVE=1 PLAYWRIGHT_BASE_URL="http://127.0.0.1:${APP_PORT}" \
  npx playwright test --grep @live
