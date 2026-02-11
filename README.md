# Mini-produit concret : **Order Tracking** (Java 21 + Spring Boot 3.4)

Exemple **copier/coller** d'une architecture **clean / hexagonal light** par feature, avec :

- API REST
- MongoDB
- Kafka
- Telemetry (Actuator + Prometheus + métriques custom)
- Job planifié type batch (cron)
- Tests multi-couches (domain, application, adapter REST, adapter Kafka)

---

## 1) Stack technique (versions stables)

- Java **21**
- Spring Boot **3.4.2**
- Spring Web
- Spring Data MongoDB
- Spring for Apache Kafka
- Spring Boot Actuator + Micrometer Prometheus
- JUnit 5 + Mockito

---

## 2) Architecture simple mais solide (ports & adapters)

Objectif : empêcher le métier d'être contaminé par HTTP, Mongo ou Kafka.

```text
src/main/java/com/example/ordertracking
├── domain
│   └── model
│       ├── Order.java
│       ├── OrderStatus.java
│       ├── OrderStatusTransitions.java
│       └── TrackingEvent.java
├── application
│   ├── port
│   │   ├── in
│   │   │   ├── RegisterOrderUseCase.java
│   │   │   ├── TrackOrderUseCase.java
│   │   │   └── UpdateOrderStatusUseCase.java
│   │   └── out
│   │       ├── LoadOrderPort.java
│   │       ├── SaveOrderPort.java
│   │       ├── LoadStaleOrdersPort.java
│   │       └── PublishOrderEventPort.java
│   └── service
│       └── OrderTrackingService.java
├── adapter
│   ├── in
│   │   └── rest
│   │       ├── OrderTrackingController.java
│   │       ├── ApiExceptionHandler.java
│   │       ├── OrderRestMapper.java
│   │       └── dto/*
│   └── out
│       ├── mongo
│       │   ├── OrderMongoAdapter.java
│       │   ├── SpringDataOrderRepository.java
│       │   ├── OrderDocument.java
│       │   └── OrderDocumentMapper.java
│       └── kafka
│           ├── KafkaOrderEventPublisher.java
│           └── OrderStatusChangedEvent.java
├── batch
│   └── StaleOrderCompletionJob.java
└── infrastructure
    └── config
        ├── KafkaConfig.java
        └── SchedulingConfig.java
```

---

## 3) Cas d'usage métier

1. `POST /api/orders` → crée une commande (`CREATED`), persiste Mongo, publie événement Kafka (et rejette les IDs déjà existants en `409`).
2. `PUT /api/orders/{id}/status` → transition métier contrôlée (`CREATED -> PACKED -> SHIPPED -> DELIVERED`).
3. `GET /api/orders/{id}` → lecture de tracking + historique.
4. Job cron `StaleOrderCompletionJob` (toutes les 30 min par défaut) : passe en `DELIVERED` les commandes `SHIPPED` depuis > 7 jours.

---

## 4) Démarrage local

### Prérequis

- Java 21
- Maven 3.9+
- MongoDB local sur `localhost:27017`
- Kafka local sur `localhost:9092`

### Lancer

```bash
mvn spring-boot:run
```

### Lancer les tests

```bash
mvn test
```

---

## 5) API REST - Exemples

### Créer une commande

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-1001","customerId":"CUS-77"}'
```

### Mettre à jour le statut

```bash
curl -X PUT http://localhost:8080/api/orders/ORD-1001/status \
  -H "Content-Type: application/json" \
  -d '{"status":"PACKED","note":"Prepared in warehouse"}'
```

### Suivre une commande

```bash
curl http://localhost:8080/api/orders/ORD-1001
```

---

## 6) Telemetry

- Health: `GET /actuator/health`
- Metrics: `GET /actuator/metrics`
- Prometheus scrape: `GET /actuator/prometheus`
- Compteur custom du job batch : `order_batch_stale_completed`
- Topic Kafka configurable : `app.kafka.order-status-topic`
- Cron configurable : `app.jobs.stale-order-completion-cron`

---

## 7) Pourquoi c'est clean

- Le **domain** ne dépend de rien (ni Spring, ni Mongo, ni Kafka).
- L'**application** décrit les ports entrants/sortants + orchestration métier.
- Les **adapters** traduisent vers REST/Mongo/Kafka.
- Les règles de transition de statuts sont centralisées dans le domain.

---

## 8) Roadmap possible

- Ajouter OpenTelemetry tracing export (OTLP)
- Ajouter consumer Kafka pour projections read-model
- Ajouter authentification JWT et multi-tenant
- Ajouter Testcontainers (Mongo+Kafka) pour tests d'intégration end-to-end
