# 04 — Docker, réseau et chaîne de vérification

## 1. Ce que démarre le laboratoire

`docker compose up --build --wait` construit l'application puis démarre les quatre services. L'interface devient disponible à **http://localhost:8080** une fois les sondes de santé satisfaites.

| Service | Image ou construction | Rôle |
|---|---|---|
| `app` | `Dockerfile`, Java 21 | WebFlux, API REST, SSE, outbox et consommateur d'audit |
| `mongo` | `mongo:8.0.26` | Commandes, outbox et audit ; replica set local `rs0` |
| `kafka` | `apache/kafka:4.3.1` | Transport des événements ; mode KRaft |
| `catalog` | `wiremock/wiremock:3.13.2` | Petite API externe contrôlable pour exercer `WebClient` |

Les images utilisent des tags de version existants, sans `latest`. Un tag Docker peut toutefois être republié : une livraison exigeant une reproductibilité binaire doit verrouiller aussi le digest après un build validé. Les tags Java fixent la version majeure et la distribution Linux, en laissant arriver les correctifs de l'image. Les commentaires du `Dockerfile` rendent cette distinction explicite.

Le `Dockerfile` emploie une étape Maven et une étape JRE. Le conteneur final ne contient ni Maven ni les sources. Java s'exécute sous l'UID `10001`, sans privilèges ; Compose lui fournit un système de fichiers en lecture seule et un `/tmp` temporaire. Le processus Java reçoit directement `SIGTERM`, grâce à la forme JSON de `ENTRYPOINT`.

`-DskipTests` dans l'étape Docker **ne valide pas le projet** : la CI fait d'abord `./mvnw verify -Pintegration`. Ce choix évite de lancer une seconde fois les tests lors du conditionnement. Le build d'image reste une étape différente de la preuve fonctionnelle.

## 2. Pourquoi Mongo tourne en replica set

L'application conserve la commande et l'événement à publier dans **deux collections**. Les deux écritures doivent appartenir à la même transaction : sinon un crash pourrait laisser une commande sans événement, ou un événement sans commande.

MongoDB ne permet pas ces transactions multi-documents sur un serveur standalone. Même avec un seul conteneur, le laboratoire initialise donc un replica set `rs0`. Le fichier `infra/mongo/replica-set.js` est exécuté par la sonde de santé :

1. Il cherche l'état du replica set.
2. Il initialise le replica set uniquement lorsque Mongo signale `NotYetInitialized`.
3. Il attend, au fil des sondes, qu'un primary accepte les écritures.
4. Il renvoie un échec pour les autres erreurs, sans les transformer en faux succès.

Un simple `ping` Mongo prouverait que le serveur répond ; il ne prouverait pas qu'une transaction peut commencer. C'est pourquoi `app` dépend de `service_healthy`, et non seulement d'un conteneur démarré.

Un seul membre n'apporte **aucune tolérance à la perte d'un serveur**. Il reproduit les capacités transactionnelles nécessaires à l'apprentissage. Une topologie de production demande plusieurs membres, des sauvegardes, une authentification, du chiffrement et une exploitation adaptée.

## 3. Comprendre les adresses réseau

Le même service possède une adresse Docker et une adresse pour l'hôte. Dans le conteneur `app`, `localhost` désigne `app` elle-même : il ne désigne ni le PC ni Mongo.

| Dépendance | Depuis `app` dans Compose | Depuis Java/IntelliJ sur l'hôte |
|---|---|---|
| Mongo | `mongodb://mongo:27017/reactive_orders?replicaSet=rs0&retryWrites=true&w=majority` | `mongodb://localhost:27017/reactive_orders?replicaSet=rs0&directConnection=true&retryWrites=true&w=majority` |
| Kafka | `kafka:29092` | `localhost:9092` |
| Catalogue | `http://catalog:8080` | `http://localhost:8089` |

Le replica set annonce le membre `mongo:27017`, nom résolu sur le réseau Compose. Le paramètre `directConnection=true` permet au client installé sur l'hôte de garder sa connexion vers le port publié, sans chercher ce nom Docker. Ce réglage est adapté au replica set local à un membre ; ce n'est pas un modèle de découverte à recopier sur un cluster de production.

Kafka possède deux listeners de données. Son listener interne annonce `kafka:29092` aux conteneurs. Son listener externe annonce `localhost:9092` au PC. Le port de bootstrap est seulement la première connexion : Kafka renvoie ensuite les adresses annoncées dans ses métadonnées. Une adresse annoncée inaccessible explique le cas classique « le port répond, mais mon client Kafka ne fonctionne pas ».

Les ports publiés sont tous liés à `127.0.0.1`. La stack reste un environnement local : Mongo et Kafka n'ont ici ni mot de passe ni TLS. Les paramètres `replication.factor=1` et `min.insync.replicas=1` correspondent uniquement à ce nœud de démonstration.

## 4. Deux façons de développer

### Tout dans Docker

Depuis le dossier contenant `compose.yaml` :

```bash
docker compose up -d --build --wait --wait-timeout 240
docker compose logs -f app
```

Accéder à http://localhost:8080. Pour arrêter en gardant les données :

```bash
docker compose down
```

`docker compose down --volumes` efface les volumes Mongo et Kafka du projet. Utiliser cette commande uniquement pour repartir volontairement d'une base vide.

### Java dans IntelliJ ou WSL, dépendances dans Docker

Cette variante facilite les points d'arrêt et la lecture des opérateurs Reactor :

```bash
./scripts/dev-infra.sh
./mvnw spring-boot:run
```

Le script ne lance que `mongo`, `kafka` et `catalog`, en attendant leurs sondes de santé. Les valeurs hôte par défaut correspondent à la configuration de développement de l'application. Sous Windows, exécuter les scripts Bash depuis WSL ; Maven dispose également de `mvnw.cmd` pour un terminal Windows.

Pour changer les ports Docker, copier `.env.example` vers `.env`, puis modifier `APP_PORT`, `MONGO_PORT`, `KAFKA_PORT` ou `CATALOG_PORT`. **Compose charge `.env`, mais Java ne le charge pas automatiquement.** Reporter les nouvelles adresses dans les variables d'environnement de la configuration IntelliJ. Ne pas exécuter `.env` comme un script shell : une URI Mongo peut contenir des caractères comme `&`.

La partie application de Compose impose ses propres adresses internes, indépendamment des variables d'URI destinées au Java de l'hôte. Le port HTTP dans le conteneur reste `8080` ; `APP_PORT` change seulement le port visible sur le PC.

## 5. Utiliser le catalogue pour apprendre `WebClient`

Les réponses sont déclarées dans `infra/catalog/mappings/` :

| Requête | Prix en centimes | Disponibilité |
|---|---:|---|
| `GET /products/KEYBOARD` | 6 990 | Disponible |
| `GET /products/MOUSE` | 2 990 | Disponible |
| `GET /products/SCREEN` | 24 900 | Disponible |
| `GET /products/OUT_OF_STOCK` | 1 490 | Indisponible |
| Autre référence | — | HTTP 404 |

Le fichier de repli utilise une priorité plus faible, afin de ne pas intercepter les produits déclarés. Le prix vient du catalogue serveur : l'interface ne choisit pas le tarif facturé.

Exercices utiles, sur cette stack locale :

1. Arrêter `catalog`, envoyer une commande, observer l'erreur HTTP et sa présentation dans l'interface, puis redémarrer le service.
2. Ajouter temporairement `"fixedDelayMilliseconds": 5000` à la réponse `keyboard.json`, redémarrer `catalog`, puis observer le timeout du client. Retirer ce délai après l'exercice.
3. Commander `OUT_OF_STOCK` et vérifier que la règle métier refuse la création.
4. Arrêter Kafka pendant une création, puis le redémarrer : vérifier que l'outbox permet la reprise et que l'audit n'est pas dupliqué. Le succès d'une création signifie que Mongo a accepté la commande et l'outbox ; la livraison Kafka est asynchrone.

Un retry doit être réservé aux erreurs qui peuvent disparaître, et aux opérations pouvant être répétées sans effet indésirable. Réessayer le GET catalogue n'a pas la même signification que réessayer une création de commande.

## 6. Vérification locale et CI

### Commandes

```bash
# Tests rapides sans dépendance à un moteur Docker.
./mvnw -B verify

# Tests Java avec de vrais services éphémères Testcontainers.
./mvnw -B verify -Pintegration

# Interface isolée, API contrôlées par les tests Playwright.
npm ci
npx playwright install chromium
npx playwright test --grep-invert @live

# Vraie stack Mongo + Kafka + application, sur des ports E2E distincts.
./scripts/test-live.sh
```

La commande avec `-Pintegration` doit échouer si Docker n'est pas disponible. Une intégration ignorée automatiquement ne constitue pas un test réussi. Le profil explicite donne le choix de lancer une suite rapide sans cacher la frontière de validation.

`scripts/test-live.sh` crée un projet Compose E2E séparé, avec ses propres volumes. Il utilise par défaut `18080`, `37017`, `19092` et `18089` pour éviter les ports du développement. Des variables `E2E_APP_PORT`, `E2E_MONGO_PORT`, `E2E_KAFKA_PORT` et `E2E_CATALOG_PORT` permettent de changer ces ports. Le nettoyage supprime uniquement les conteneurs et volumes de ce projet E2E, même après un échec.

Playwright utilise `PLAYWRIGHT_LIVE=1` pour désactiver son serveur statique et `PLAYWRIGHT_BASE_URL` pour viser la vraie application. Sans ce mode, les API sont simulées : les tests isolés valident l'interface, pas les écritures Mongo ni les échanges Kafka.

### Pipeline fourni

Le workflow `.github/workflows/ci.yml` :

1. Prépare Java 21 et Node 24 sur Ubuntu.
2. Vérifie qu'un moteur Docker est disponible.
3. Exécute `./mvnw -B -ntp verify -Pintegration`.
4. Installe les versions npm du lockfile et Chromium.
5. Exécute les tests UI isolés et conserve leurs rapports.
6. Construit puis attend la stack complète.
7. Exécute les parcours `@live` sur l'application réelle.
8. Capture les logs en cas d'échec et nettoie toujours la stack.
9. Conserve les rapports Java et les rapports navigateur.

Les permissions GitHub sont limitées à `contents: read`. Le workflow n'utilise aucun secret applicatif et ne publie aucune image. Les actions emploient des tags majeurs réellement vérifiés ; ces tags ne sont pas des identifiants immuables de commit. Un verrouillage SHA et une politique de mises à jour peuvent être ajoutés après validation de la chaîne.

### Si ce projet est rangé dans un sous-dossier Git

GitHub ne détecte les workflows que dans `.github/workflows/` **à la racine du dépôt**. Le workflow livré dans le projet constitue le modèle pour un dépôt autonome.

Pour l'intégrer sous `reactive-orders-lab/` dans un dépôt existant, la copie active doit être placée à la racine, par exemple `.github/workflows/reactive-orders-lab.yml`, avec :

```yaml
defaults:
  run:
    working-directory: reactive-orders-lab
```

Adapter également `cache-dependency-path` de `setup-java` vers `reactive-orders-lab/pom.xml`, celui de `setup-node` vers `reactive-orders-lab/package-lock.json`, et préfixer les chemins d'artefacts par `reactive-orders-lab/`. Les actions `uses:` ne suivent pas `defaults.run.working-directory`. Ajouter des filtres `paths` couvrant le projet et son workflow évite de relancer ses tests pour les autres dossiers du dépôt.

## 7. État réel de la validation de cette livraison

Les fichiers JSON, YAML et la syntaxe des scripts Bash ont été vérifiés lors de la création. Le runtime de création ne dispose pas de moteur Docker : **le build d'image, le démarrage Compose et les tests Docker ne doivent donc pas être présentés comme exécutés dans ce runtime**. La CI et les commandes ci-dessus constituent les étapes reproductibles prévues pour les exécuter. Le rapport global de livraison précise séparément les tests réellement lancés.

Cette limite concerne l'environnement de vérification ; elle ne transforme pas une configuration simplement écrite en résultat testé. Si la CI révèle une incompatibilité d'image ou une course au démarrage, conserver son log et corriger la configuration avant de considérer la chaîne validée.

## Sources officielles

- [MongoDB : transactions et replica sets](https://www.mongodb.com/docs/manual/core/transactions/).
- [MongoDB 8.0 : notes de version](https://www.mongodb.com/docs/v8.0/release-notes/8.0/).
- [Image officielle Mongo 8.0.26](https://hub.docker.com/_/mongo/tags?name=8.0.26).
- [Apache Kafka : distributions et images Docker](https://kafka.apache.org/community/downloads/).
- [Exemples Docker officiels Kafka](https://github.com/apache/kafka/tree/trunk/docker/examples).
- [WireMock : exécution Docker](https://wiremock.org/docs/standalone/docker/).
- [WireMock Docker : sonde intégrée](https://github.com/wiremock/wiremock-docker).
- [Propriétés Spring Boot, notamment `spring.mongodb.uri`](https://docs.spring.io/spring-boot/appendix/application-properties/index.html).
- [Actions officielles : checkout](https://github.com/actions/checkout), [Java](https://github.com/actions/setup-java), [Node](https://github.com/actions/setup-node), [artefacts](https://github.com/actions/upload-artifact).
