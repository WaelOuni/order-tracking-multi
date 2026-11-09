#!/usr/bin/env bash
# Démarre uniquement les dépendances : l'app peut être déboguée dans IntelliJ.
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"
docker compose version >/dev/null
docker info >/dev/null
docker compose up -d --wait --wait-timeout 180 mongo kafka catalog
cat <<'HELP'
Dépendances prêtes. Démarrer l'application dans un autre terminal :
  ./mvnw spring-boot:run

Valeurs hôte par défaut :
  Mongo   mongodb://localhost:27017/reactive_orders?replicaSet=rs0&directConnection=true
  Kafka   localhost:9092
  HTTP    http://localhost:8089

Si les ports .env ont changé, reporter aussi les URI dans la configuration
d'exécution de l'IDE. Docker Compose charge .env ; Java ne le charge pas seul.
Arrêt sans supprimer les données : docker compose stop
HELP
