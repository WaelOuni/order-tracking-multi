# 06 — Parcours d’apprentissage et exercices

Ce parcours part de ton expérience Java/Spring et de tes sujets commandes/Kafka. L’objectif n’est pas de mémoriser des noms d’opérateurs : il est de pouvoir expliquer **quand le travail démarre, ce qui est effectivement garanti et ce qui se passe lors d’une panne**.

Travaille sur une branche d’exercice. Avant chaque modification, écris ta prédiction, lance le test existant, modifie une seule idée, puis explique la différence observée. Les parties « Résultat attendu » ci-dessous sont des critères d’exercice, pas des résultats prétendument mesurés sur ta machine.

## Plan de travail

| Étape | Sujet | Niveau |
|---|---|---|
| 1 | Domaine immuable et invariants | Prise en main |
| 2 | Paresse, souscription et `defer` | Fondations Reactor |
| 3 | `map`, `flatMap`, `thenReturn`, absence et erreur | Composition |
| 4 | Concurrence, ordre et demande | Raisonnement sur les flux |
| 5 | Timeouts et retries ciblés | Intégration HTTP |
| 6 | Idempotence HTTP après réponse perdue | Fiabilité distribuée |
| 7 | Transaction Mongo et fenêtre de crash outbox | Cohérence |
| 8 | Ack Kafka et déduplication du consommateur | Livraison d’événements |
| 9 | Pagination par curseur | Évolution de contrat |
| 10 | Observer, diagnostiquer et justifier l’architecture | Synthèse Tech Lead |

Les exercices 1 à 4 peuvent être étudiés avec les tests rapides. Les preuves Mongo/Kafka des exercices suivants nécessitent Docker et le profil d’intégration. Une correction purement Mockito ne remplace pas un test de transaction réelle.

## Exercice 1 — Retrouver les règles dans le domaine

**Fichiers :** [`Order.java`](../src/main/java/fr/wael/reactiveorders/domain/Order.java), [`Product.java`](../src/main/java/fr/wael/reactiveorders/domain/Product.java), [`OrderTest.java`](../src/test/java/fr/wael/reactiveorders/domain/OrderTest.java), [`ArchitectureTest.java`](../src/test/java/fr/wael/reactiveorders/architecture/ArchitectureTest.java).

**Travail demandé :** explique pourquoi une référence client, une quantité et un prix ne sont pas acceptés sans contrôle même si le formulaire HTML les a déjà vérifiés. Écris un test vérifiant que `cancel()` produit l’état annulé sans modifier l’objet d’origine. Ajoute un cas limite de référence de 64 caractères et de 65 caractères si le cas n’est pas déjà couvert ; évite de dupliquer un test existant.

**Résultat attendu :** l’objet d’origine reste `CREATED`, l’objet retourné est `CANCELLED`, la limite métier s’applique sans Spring, et les règles ArchUnit restent satisfaites. Tu dois pouvoir expliquer pourquoi le domaine ne dépend ni de Mongo ni de Reactor.

**Vérification :** `./mvnw -Dtest=OrderTest,ProductTest,ArchitectureTest test`.

## Exercice 2 — Identifier exactement le démarrage d’une opération

**Fichiers :** [`ReactorLearningTest.java`](../src/test/java/fr/wael/reactiveorders/learning/ReactorLearningTest.java), [`OrderService.java`](../src/main/java/fr/wael/reactiveorders/application/OrderService.java), [`OrderServiceTest.java`](../src/test/java/fr/wael/reactiveorders/application/OrderServiceTest.java).

**Travail demandé :** lis `coldPublisherIsLazyAndRunsOncePerSubscription`, prédis le compteur avant et après deux souscriptions, puis exécute-le. Dans une copie locale du test, remplace `Mono.defer(() -> Mono.just(...))` par `Mono.just(...)`. Compare le moment de l’incrément. Retrouve ensuite les deux raisons d’utiliser `defer` dans `OrderService.create()`.

**Résultat attendu :** tu distingues la construction de la chaîne de son exécution. Tu sais expliquer pourquoi deux souscriptions à un publisher froid peuvent relancer des effets, et pourquoi construire une alternative à `switchIfEmpty` n’est pas toujours neutre.

**Vérification :** `./mvnw -Dtest=ReactorLearningTest,OrderServiceTest test`. Rétablis le test de référence après l’expérience.

## Exercice 3 — Composer sans créer un `Mono<Mono<...>>`

**Fichiers :** [`OrderService.java`](../src/main/java/fr/wael/reactiveorders/application/OrderService.java), [`CatalogPort.java`](../src/main/java/fr/wael/reactiveorders/application/port/CatalogPort.java), [`OutboxStore.java`](../src/main/java/fr/wael/reactiveorders/application/port/OutboxStore.java), [`OrderServiceTest.java`](../src/test/java/fr/wael/reactiveorders/application/OrderServiceTest.java).

**Travail demandé :** annote sur papier le type de chaque étape de `create()` : lecture, produit, commande, écriture outbox, résultat. Explique pourquoi la création d’un objet métier utilise `map`, la sauvegarde `flatMap`, et la sortie d’outbox `thenReturn`. Examine séparément un catalogue vide, une erreur réseau et un produit indisponible.

**Résultat attendu :** une absence de produit donne une erreur explicite ; une panne reste une panne ; aucune commande ni événement ne doit être écrit lorsqu’un prérequis échoue. Tu ne dois pas ajouter `subscribe()` pour « déclencher » une sauvegarde à l’intérieur du cas d’usage.

**Vérification :** les tests `missingCatalogueItemDoesNotPersistAnything`, `unavailableCatalogueIsNotMistakenForAMissingProduct` et `outboxFailurePropagatesOutOfTheUnitOfWork` rendent ces distinctions concrètes.

## Exercice 4 — Ne pas confondre ordre, concurrence et backpressure

**Fichiers :** [`ReactorLearningTest.java`](../src/test/java/fr/wael/reactiveorders/learning/ReactorLearningTest.java), [`OutboxRelay.java`](../src/main/java/fr/wael/reactiveorders/application/OutboxRelay.java), [`EventsController.java`](../src/main/java/fr/wael/reactiveorders/adapter/in/web/EventsController.java).

**Travail demandé :** compare les tests `concatMap` et `flatMap` à temps virtuel. Prédis le résultat si la concurrence de `flatMap` passe de 3 à 1. Dans le test de demande, identifie le nombre d’éléments émis avant le premier `thenRequest`. Retrouve enfin ce que `onBackpressureDrop()` abandonne dans le contrôleur SSE.

**Résultat attendu :** concurrence 1 empêche le chevauchement des opérations de cet exemple ; concurrence 3 permet des terminaisons dans un autre ordre. Le contrôleur SSE abandonne des **ticks de polling**, pas directement des commandes ni des messages Kafka.

**Question de revue :** si l’on remplace `concatMap` par `flatMap(..., 20)` dans le relay, quelles capacités faut-il examiner et quelles garanties changent ? Une augmentation de concurrence ne prouve pas un gain de débit utile.

## Exercice 5 — Faire échouer proprement une dépendance REST

**Fichiers :** [`WebClientCatalog.java`](../src/main/java/fr/wael/reactiveorders/adapter/out/http/WebClientCatalog.java), [`keyboard.json`](../infra/catalog/mappings/keyboard.json), [`ApiExceptionHandler.java`](../src/main/java/fr/wael/reactiveorders/adapter/in/web/ApiExceptionHandler.java), [`app.js`](../src/main/resources/static/app.js).

**Travail demandé :** sur la stack locale, ajoute temporairement `fixedDelayMilliseconds: 5000` dans la réponse WireMock du clavier. Prédis l’effet du timeout par tentative et des deux retries, puis observe l’erreur HTTP finale. Compare avec un produit inconnu et avec `OUT_OF_STOCK`. Restaure ensuite la fixture.

**Résultat attendu :** la panne ou lenteur transitoire finit par une erreur de service ; un produit inconnu est traité comme une absence ; un produit indisponible est refusé par le métier. Aucun prix inventé ne transforme l’échec catalogue en succès. Le nombre maximal de tentatives est trois, pas deux.

**Extension de test :** ajoute un test d’adaptateur HTTP avec un serveur de test contrôlé, dans un nouveau fichier `src/test/java/fr/wael/reactiveorders/adapter/out/http/WebClientCatalogTest.java`. Vérifie le nombre de requêtes pour un 503 temporaire puis un succès, et l’absence de retry métier. Ce chemin est un fichier **à créer**, pas une suite déjà livrée.

## Exercice 6 — Une commande enregistrée sans réponse reçue

**Fichiers :** [`app.js`](../src/main/resources/static/app.js), [`orders.spec.js`](../ui-tests/orders.spec.js), [`OrderService.java`](../src/main/java/fr/wael/reactiveorders/application/OrderService.java), [`MongoOrderStore.java`](../src/main/java/fr/wael/reactiveorders/adapter/out/mongo/MongoOrderStore.java).

**Travail demandé :** lis le scénario navigateur « réutilise la clé si la réponse de création est perdue ». Identifie la ligne qui simule le commit et celle qui supprime la réponse. Explique pourquoi l’interface conserve la clé. Retrouve côté Java la protection du cas où deux requêtes ont toutes deux lu « commande absente » avant de tenter l’insertion.

**Résultat attendu :** deux demandes de même contenu et même clé conduisent à une commande, avec la même identité. Même clé et contenu différent produisent un conflit. Après une annulation, rejouer la création avec la même clé ne recrée pas la commande.

**Extension :** conçois une reprise par `sessionStorage`, sans commencer par l’implémenter. Définis la durée, le contenu minimal mémorisé, le comportement après succès, la consultation de l’opération et ce qui se passe lorsque l’utilisateur veut réellement une deuxième commande identique. La mémoire navigateur actuelle ne survit pas à un rechargement complet.

## Exercice 7 — Raisonner sur deux fenêtres de panne différentes

**Fichiers :** [`MongoUnitOfWork.java`](../src/main/java/fr/wael/reactiveorders/adapter/out/mongo/MongoUnitOfWork.java), [`OrderService.java`](../src/main/java/fr/wael/reactiveorders/application/OrderService.java), [`OutboxRelay.java`](../src/main/java/fr/wael/reactiveorders/application/OutboxRelay.java), [`OutboxRelayTest.java`](../src/test/java/fr/wael/reactiveorders/application/OutboxRelayTest.java), [`FullStackIT.java`](../src/test/java/fr/wael/reactiveorders/integration/FullStackIT.java).

**Travail demandé :** compare une panne entre les deux écritures Mongo et une panne après l’ack Kafka mais avant `markPublished`. Pour chacune, écris ce qui peut être durable et ce qui doit être retenté. Exécute le test de rollback réel et lis le test `failureAfterBrokerAcknowledgementRetainsTheSameEventIdentityForRedelivery`.

**Résultat attendu :** la transaction Mongo rend atomiques commande et outbox. Elle ne fusionne pas le commit Mongo du relay avec l’ack Kafka. La seconde fenêtre peut donc produire une republication ; le même `eventId` doit être conservé.

**Vérification :** `./mvnw verify -Pintegration`. Une indisponibilité Docker doit être visible ; ne pas remplacer cette preuve par un test qui ignore silencieusement la suite.

## Exercice 8 — Acquitter au bon moment et dédupliquer atomiquement

**Fichiers :** [`OrderEventsListener.java`](../src/main/java/fr/wael/reactiveorders/adapter/in/kafka/OrderEventsListener.java), [`MongoAuditStore.java`](../src/main/java/fr/wael/reactiveorders/adapter/out/mongo/MongoAuditStore.java), [`KafkaEventPublisher.java`](../src/main/java/fr/wael/reactiveorders/adapter/out/kafka/KafkaEventPublisher.java), [`KafkaConfiguration.java`](../src/main/java/fr/wael/reactiveorders/configuration/KafkaConfiguration.java), [`FullStackIT.java`](../src/test/java/fr/wael/reactiveorders/integration/FullStackIT.java).

**Travail demandé :** explique pourquoi le listener renvoie son `Mono<Void>` à Spring au lieu d’appeler `subscribe()` puis de revenir immédiatement. Examine la déduplication : pourquoi un simple `existsById` avant `insert` ne suffirait-il pas ? Quelle différence entre un doublon identique et un même ID dont le contenu est différent ?

**Résultat attendu :** l’achèvement du traitement conditionne l’acquittement ; l’index unique tranche les insertions concurrentes ; un conflit de contenu n’est pas accepté comme doublon bénin. Le test poison vérifie une DLT observable.

**Question de revue :** la clé Kafka `orderId` suffit-elle à garantir l’ordre de fin des `Mono` retournés par le listener ? Réponds aussi à cette question pour deux instances du relay. L’audit a été choisi pour rester indépendant de cet ordre.

## Exercice 9 — Passer de l’offset à un curseur composé

**Fichiers à modifier :** [`MongoOrderStore.java`](../src/main/java/fr/wael/reactiveorders/adapter/out/mongo/MongoOrderStore.java), [`OrderStore.java`](../src/main/java/fr/wael/reactiveorders/application/port/OrderStore.java), [`OrderService.java`](../src/main/java/fr/wael/reactiveorders/application/OrderService.java), [`OrderPage.java`](../src/main/java/fr/wael/reactiveorders/application/OrderPage.java), [`OrderController.java`](../src/main/java/fr/wael/reactiveorders/adapter/in/web/OrderController.java), [`app.js`](../src/main/resources/static/app.js) et leurs tests.

**Travail demandé :** remplace le contrat `page/size` par `after/size`, avec un curseur contenant la dernière paire `(createdAt, id)`. Conserve le tri `createdAt DESC, _id ASC`. Définis la validation d’un curseur illisible et la façon dont le bouton Précédente conserve les curseurs déjà visités.

**Résultat attendu :** sur un jeu de données inchangé, deux pages successives ne se chevauchent pas, y compris si plusieurs dates sont égales. Ajouter un nouvel élément en tête ne doit pas décaler le point de reprise de la page suivante. Le client ne doit pas pouvoir injecter librement des critères Mongo à travers son curseur.

**Tests à ajouter :** dates égales, curseur invalide, dernière page, insertion récente entre deux lectures, borne `size`. La pagination par curseur réduit ce type de déplacement ; elle ne crée pas à elle seule une photographie transactionnelle de toutes les pages.

## Exercice 10 — Présenter le système comme en revue Tech Lead

**Fichiers :** [`pom.xml`](../pom.xml), [`ApplicationConfiguration.java`](../src/main/java/fr/wael/reactiveorders/configuration/ApplicationConfiguration.java), [`OutboxRelay.java`](../src/main/java/fr/wael/reactiveorders/application/OutboxRelay.java), [`EventsController.java`](../src/main/java/fr/wael/reactiveorders/adapter/in/web/EventsController.java), [`compose.yaml`](../compose.yaml), [chapitre Docker/CI](04-docker-et-ci.md), [chapitre UI/tests](05-ui-et-tests.md).

**Travail demandé :** prépare une explication de cinq minutes couvrant une création normale, une panne catalogue, une panne Kafka et une réponse HTTP perdue. Justifie trois dépendances, l’absence de driver Mongo bloquant et l’isolation du démarrage d’envoi Kafka sur `boundedElastic`. Identifie les métriques utiles sans mettre un `orderId` comme tag métrique.

**Résultat attendu :** tu distingues le temps HTTP, le délai outbox, le traitement Kafka et l’affichage SSE. Tu proposes des métriques bornées, par exemple nombre de tentatives par résultat et ancienneté des événements en attente. Tu sais nommer les limites du laboratoire : authentification absente, broker unique, snapshots incomplets, données de démonstration et vérifications nécessitant Docker.

**Livrable :** une note de revue courte indiquant problème, décision, conséquences et preuve prévue. Ne qualifie pas une modification de « production ready » uniquement parce que les tests unitaires passent. Une amélioration doit être reliée à un risque concret et à une vérification adaptée.

---

## Pistes et corrections — à lire après avoir cherché

### 1. Domaine

Un contrôleur n’est qu’une porte d’entrée. Les tests, un consommateur ou un futur batch peuvent construire un objet sans passer par HTTP. Le constructeur de l’agrégat impose donc les mêmes invariants. Le record est immuable ; `cancel()` renvoie une autre valeur sans setter. Pour un ordre déjà annulé, il peut renvoyer `this`, puisqu’aucun état ne doit changer.

### 2. Paresse

Avec `defer`, le compteur reste à zéro avant souscription, puis produit un et deux. Avec `just(expression)`, Java évalue d’abord `expression` : l’effet se produit lors de la construction, puis la même valeur est réémise. Dans `create`, le `defer` externe retarde l’appel au port ; celui de l’alternative retarde le chemin de création tant qu’on n’a pas établi l’absence.

### 3. Composition

`map` attend une fonction `T → R` ; `flatMap` compose `T → Mono<R>`. Après `outbox.append`, le résultat est un `Mono<Void>` : il exprime une fin de travail, pas une valeur `null` à transmettre. `thenReturn(saved)` conserve la commande après cette fin. `switchIfEmpty` traite une complétion sans valeur ; il ne traite pas `onError`.

### 4. Demande et concurrence

La demande initiale nulle du `StepVerifier` interdit les émissions de `Flux.range` tant que `thenRequest` n’a pas été appelé. Dans le test temporisé, `flatMap(..., 3)` permet aux délais courts de terminer avant les longs ; `concatMap` attend chaque opération. Augmenter la concurrence du relay augmente la pression sur Kafka/Mongo et peut réordonner les résultats. Cela ne définit pas l’ordre global de plusieurs processus.

### 5. Timeout et retry

Le timeout est placé avant `retryWhen` : chaque nouvelle souscription obtient sa propre fenêtre de deux secondes. Deux retries signifient une première tentative plus deux reprises. Les délais de backoff et le jitter rendent une durée exacte moins utile qu’une borne raisonnée. Ne pas réessayer une règle métier invalide ; ne pas supprimer l’erreur avec un produit de secours inventé.

### 6. Idempotence

La première lecture seule ne protège pas d’une course. Deux transactions peuvent lire l’absence. `insert` et l’index unique `_id` désignent le gagnant. Après l’échec de la transaction perdante, la relecture vérifie que le contenu correspond. `save` utilisé comme upsert à la place d’`insert` risquerait d’écraser l’opération existante. Une clé doit représenter une intention, pas une tentative réseau.

### 7. Outbox

Mongo garantit l’atomicité de ses deux écritures dans la transaction. Kafka est en dehors de cette transaction. Après son ack, le processus peut tomber avant le marquage Mongo : la reprise republie. Un lease limite deux propriétaires actifs d’un même événement ; il ne supprime pas la fenêtre de crash. La déduplication du consommateur reste nécessaire.

### 8. Consommateur

Renvoyer le `Mono` permet au container Spring de suivre la vraie fin du traitement. Un `subscribe()` interne détacherait le traitement du retour du listener. L’unicité d’`eventId` protège la concurrence ; comparer le contenu protège contre une collision anormale. Le keying Kafka n’empêche pas des traitements asynchrones de terminer dans un autre ordre. Si un futur consommateur met à jour un état séquentiel, il faudra une conception adaptée : versions monotones ou sérialisation explicite, selon le besoin.

### 9. Curseur

Avec le tri choisi, la page suivant `(T, I)` cherche `createdAt < T` **ou** `(createdAt = T et _id > I)`. Les comparaisons doivent utiliser les mêmes types et la même collation que le tri. Un UUID v4 seul n’exprime pas l’ordre chronologique ; c’est la paire qui définit le point de reprise. Ajouter un index composé cohérent, borner les lectures et tester les égalités de date. Pour une photographie figée, il faut un contrat supplémentaire.

### 10. Diagnostic

Une requête HTTP rapide peut laisser une outbox vieillissante si Kafka est indisponible. Un audit peut arriver après la réponse HTTP ; l’interface doit le rendre compréhensible. Des tags contenant chaque UUID créent une cardinalité non bornée : conserver ces identifiants dans des logs/traces adaptés, et des dimensions limitées dans les métriques. Une revue utile relie chaque garantie à son mécanisme et à son test, puis nomme les cas non couverts.

## Références pour aller plus loin

- [Reactor : documentation de référence et tests](https://projectreactor.io/docs/core/release/reference/testing.html).
- [Reactor : choix des opérateurs](https://projectreactor.io/docs/core/release/reference/apdx-operatorChoice.html).
- [Spring Kafka : types de retour asynchrones](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/async-returns.html).
- [MongoDB : pagination avec `skip` et requêtes par plage](https://www.mongodb.com/docs/manual/reference/method/cursor.skip/).
- [Playwright : API simulées](https://playwright.dev/docs/mock).

Les fichiers du projet sont la référence pour les assertions propres à ce laboratoire. Les liens externes expliquent les mécanismes de plateforme ; ils ne constituent pas une preuve que ta stack locale ou une nouvelle modification a été validée.
