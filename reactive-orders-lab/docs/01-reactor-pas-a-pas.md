# 01 — Comprendre Reactor en suivant une vraie commande

L'objectif de ce laboratoire est de savoir **expliquer ce qui s'exécute, quand, sur quel thread, et ce qui se passe en cas d'erreur**. Remplacer `Order` par `Mono<Order>` ne suffit pas. La valeur pédagogique vient de la composition des appels HTTP, des écritures Mongo, de la transaction, puis de la livraison Kafka.

Tu peux suivre ce chapitre avec l'IDE ouvert. Les commentaires du code expliquent les opérateurs à l'endroit où ils interviennent ; ce document relie ces décisions entre elles.

## 1. Parcours de lecture

| Fichier | Question à se poser |
|---|---|
| [OrderController](../src/main/java/fr/wael/reactiveorders/adapter/in/web/OrderController.java) | Pourquoi retourner un `Mono` sans appeler `subscribe()` ? |
| [OrderService](../src/main/java/fr/wael/reactiveorders/application/OrderService.java) | Où commence le travail ? Où est la transaction ? |
| [WebClientCatalog](../src/main/java/fr/wael/reactiveorders/adapter/out/http/WebClientCatalog.java) | Quels échecs méritent un retry ? |
| [MongoUnitOfWork](../src/main/java/fr/wael/reactiveorders/adapter/out/mongo/MongoUnitOfWork.java) | Comment la session Mongo suit-elle une chaîne asynchrone ? |
| [KafkaEventPublisher](../src/main/java/fr/wael/reactiveorders/adapter/out/kafka/KafkaEventPublisher.java) | Pourquoi une API qui renvoie une future nécessite-t-elle un scheduler ? |
| [OutboxRelay](../src/main/java/fr/wael/reactiveorders/application/OutboxRelay.java) | Pourquoi séquencer les envois et borner leur attente ? |
| [EventsController](../src/main/java/fr/wael/reactiveorders/adapter/in/web/EventsController.java) | Que ralentit réellement la backpressure ? |
| [ReactorLearningTest](../src/test/java/fr/wael/reactiveorders/learning/ReactorLearningTest.java) | Comment rendre ces règles observables sans `Thread.sleep` ? |

## 2. `Mono` et `Flux` décrivent des signaux

Un `Mono<Order>` représente un résultat asynchrone contenant **zéro ou une commande**. Il peut réussir avec une valeur, réussir sans valeur, ou échouer. Un `Flux<Order>` représente une suite de zéro à plusieurs commandes. La quantité d'éléments n'indique ni le nombre de threads ni la vitesse du traitement.

| Type ou signal | Signification dans ce projet |
|---|---|
| `Mono<Order>` avec une valeur | Une commande a été trouvée ou créée |
| `Mono.empty()` | La recherche ne trouve aucun document |
| `Mono.error(...)` | L'opération a échoué |
| `Mono<Void>` terminé | Une opération sans valeur de retour a réussi, par exemple l'insertion dans l'outbox |
| `Flux<Order>` | Plusieurs documents arrivent du pilote Mongo |

Une base indisponible ne doit donc pas devenir `Mono.empty()`. Sinon le cas d'usage pourrait comprendre « cette commande n'existe pas » alors que la réponse est « je n'ai pas pu vérifier ». Dans `OrderService.get`, seule l'absence réelle devient une erreur applicative 404 avec `switchIfEmpty`.

Un publisher émet des `onNext`, puis un signal terminal `onComplete` ou `onError`. Après un signal terminal, il n'émet plus de valeur. Reactor ne transporte pas une valeur `null` dans `onNext` ; représenter l'absence avec un publisher vide.

Source : [contrat de `Mono`](https://projectreactor.io/docs/core/release/reference/coreFeatures/mono.html).

## 3. Les étapes de vie d'une chaîne

Il faut distinguer **l'assemblage** de la chaîne de son **exécution** :

1. Le code construit une succession d'opérateurs.
2. Un abonné souscrit à cette succession.
3. L'abonné exprime une demande d'éléments.
4. La source travaille et les opérateurs transmettent les signaux.
5. La chaîne termine ou son abonnement est annulé.

Dans une requête HTTP, WebFlux joue le rôle de l'abonné. Il relie aussi la chaîne à la réponse et à la connexion du client. Le contrôleur retourne la description du travail ; il ne démarre pas un travail détaché de la requête.

```java
// Correct : WebFlux voit le résultat, les erreurs et l'annulation.
return service.create(command);

// Incorrect dans un contrôleur : résultat et erreur sont détachés de la réponse.
service.create(command).subscribe();
return Mono.just("accepté");
```

Dans la deuxième forme, le navigateur pourrait lire « accepté » avant l'écriture Mongo. Il ne recevrait pas l'erreur d'une transaction échouée. Le même défaut dans un listener Kafka pourrait entraîner un acquittement avant la persistance de l'audit.

Les abonnements de ce projet appartiennent aux frontières prévues : WebFlux, Spring Kafka, le scheduler réactif Spring et les outils de test. Le métier ne prend pas leur place.

## 4. `defer`, `just` et le moment de l'effet

Considérons :

```java
Mono<Instant> assembledNow = Mono.just(clock.instant());
Mono<Instant> subscribedNow = Mono.defer(() -> Mono.just(clock.instant()));
```

Dans la première ligne, Java appelle `clock.instant()` avant d'appeler `Mono.just`. Toutes les souscriptions reçoivent cette valeur capturée. Dans la seconde, la lambda est exécutée à chaque souscription : la date est recalculée.

Même distinction avec une dépendance :

```java
// L'appel a déjà eu lieu quand Mono.just reçoit son argument.
Mono.just(legacyClient.load());

// L'appel aura lieu à la souscription, mais reste potentiellement bloquant.
Mono.fromCallable(legacyClient::load);
```

**Différer un appel ne le rend pas non bloquant.** S'il bloque, il faut encore l'isoler sur un scheduler adapté. C'est précisément le travail de l'adaptateur Kafka décrit plus loin.

`OrderService.create` utilise `Mono.defer` autour du cas d'usage. Cela diffère l'appel aux ports et capture les exceptions synchrones de l'assemblage dans le protocole réactif. Son alternative `switchIfEmpty(Mono.defer(...))` n'est construite et exécutée que lorsque la commande n'existe pas.

`OutboxRelay.runOnce` possède une autre raison essentielle de différer : Spring peut obtenir une fois le publisher d'une méthode `@Scheduled`, puis y souscrire à chaque déclenchement. Les dates de lease et les tokens doivent être créés à chaque passage, pas une fois au démarrage. Voir [scheduling réactif Spring](https://docs.spring.io/spring-framework/reference/integration/scheduling.html).

## 5. Cold, hot, partage et cache

Une source **cold** produit un travail pour chaque souscription. Les appels Mongo et HTTP de ce laboratoire suivent ce modèle : souscrire deux fois peut produire deux appels. Une source **hot** est liée à une production déjà en cours ou partagée : les abonnés ne déclenchent pas chacun une copie indépendante du même travail.

La distinction porte sur la relation entre production et abonnés, pas sur la présence d'un timer. Ici, chaque connexion à `/api/events` obtient son propre `Flux.interval` et son propre polling Mongo : le suivi SSE n'est pas un flux global partagé.

Un opérateur comme `cache()` change les conséquences des souscriptions suivantes. Il peut conserver une valeur, une erreur ou une terminaison vide. L'ajouter à une recherche de commande ou à un traitement métier pour « aller plus vite » peut conserver un état périmé ou masquer une future tentative. Aucun cache partagé n'est ajouté à ces opérations dans le laboratoire.

Pour un journal live partagé, un `Sinks.Many` serait envisageable, mais il faudrait définir la mémoire maximale, la politique des clients lents, le nettoyage et la reprise après déconnexion. Un sink mémoire ne remplace pas un journal durable. Voir [sources hot et cold](https://projectreactor.io/docs/core/release/reference/advancedFeatures/reactor-hotCold.html).

## 6. Lire la création de commande ligne par ligne

La version suivante simplifie `OrderService.create` pour mettre en évidence l'enchaînement. Le fichier réel ajoute notamment la vérification de la clé d'idempotence et la course entre requêtes simultanées.

```java
return Mono.defer(() -> orders.findById(command.requestId()) // Chercher à la souscription.
    .switchIfEmpty(Mono.defer(() ->                         // Brancher uniquement si absent.
        catalog.findProduct(command.sku())                 // GET réactif du vrai catalogue.
            .map(product -> Order.create(                  // Calcul métier court, sans I/O.
                command.requestId(), command.customerRef(),
                product, command.quantity(), clock.instant()))
            .flatMap(order -> transactions.transactional(  // Composer un autre publisher.
                orders.insert(order)                      // Première écriture Mongo.
                    .flatMap(saved -> outbox.append(        // Attendre la version persistée.
                        OrderEvent.of(saved, "ORDER_CREATED", clock.instant()))
                        .thenReturn(saved)))))));          // Rendre la valeur après succès.
```

Le `map` transforme un `Product` en `Order`. Le `flatMap` suivant transforme la valeur `Order` en une **opération asynchrone** produisant une `Order` sauvegardée. La transaction contient l'insertion de commande et l'insertion d'outbox, ensemble. L'appel HTTP catalogue reste en dehors : il serait inutile de conserver une transaction ouverte pendant l'attente d'un service distant.

`thenReturn(saved)` n'est pas une instruction « réussir quoi qu'il arrive ». Si `outbox.append` échoue, l'erreur continue et la transaction est annulée.

## 7. Choisir l'opérateur selon le contrat

| Opérateur | À utiliser pour | Exemple réel |
|---|---|---|
| `map` | Calcul synchrone court `T → R` | `OrderResponse.from(order)` |
| `flatMap` sur `Mono` | Composer `T → Mono<R>` | Commande sauvegardée → insertion d'outbox |
| `flatMap` sur `Flux` | Lancer plusieurs traitements, avec concurrence explicitement choisie | Démonstration dans les tests d'apprentissage |
| `concatMap` | Attendre la fin d'un traitement avant le suivant | Relay outbox, polling SSE |
| `switchIfEmpty` | Remplacer une absence réelle | Commande introuvable → 404 |
| `onErrorMap` | Traduire une erreur en conservant l'échec | Panne catalogue → erreur applicative 503 |
| `onErrorResume` | Choisir un autre publisher après un échec précis | Relecture après une collision sur la clé de commande |
| `then` | Attendre la fin, ignorer la valeur précédente | Confirmation Kafka → marquage outbox |
| `thenReturn` | Attendre le succès puis rendre une valeur connue | Insertion d'événement → commande sauvegardée |
| `collectList` | Regrouper un flux fini et borné | Au maximum `size + 1` commandes |

Ce code produit le mauvais type :

```java
Mono<Mono<Order>> nested = catalog.findProduct(sku)
    .map(product -> saveOrder(product));
```

Il transporte un publisher comme valeur. Il faut composer :

```java
Mono<Order> saved = catalog.findProduct(sku)
    .flatMap(product -> saveOrder(product));
```

Sur un `Flux`, `flatMap` peut terminer les traitements dans un autre ordre que les entrées. `concatMap` les séquence, avec un coût potentiel en débit. Dans le relay, ce coût est accepté pour simplifier les envois et limiter le travail simultané. Il ne fournit toutefois pas un ordre métier global entre plusieurs instances, retries et partitions ; le chapitre fiabilité explique cette frontière.

## 8. Le piège de `Mono<Void>` et des sources vides

`Mono<Void>` signale un achèvement sans valeur. Un `flatMap` attend une valeur pour appeler sa lambda :

```java
// La lambda n'est pas appelée : append ne produit aucun onNext.
outbox.append(event).flatMap(ignored -> sendResponse());

// sendResponse est composé après l'achèvement réussi de append.
outbox.append(event).then(Mono.defer(this::sendResponse));
```

Même vigilance avec `zip` : il combine des **valeurs**, pas uniquement des terminaisons. Zipper des `Mono<Void>` ne donne pas un couple de résultats. Pour attendre plusieurs terminaisons sans résultat, `Mono.when(...)` exprime une autre intention ; pour des étapes qui dépendent les unes des autres, employer `then` ou `flatMap` selon la cardinalité.

Ne pas remplacer mécaniquement toutes les compositions par `Mono.when` : les écritures d'une même transaction Mongo sont ici séquentielles, et la création de l'événement dépend de la version attribuée à la commande sauvegardée.

Source : [FAQ Reactor sur les sources vides et `zip`](https://projectreactor.io/docs/core/release/reference/faq.html).

## 9. Threads, event loops et schedulers

Reactor ne crée pas un thread pour chaque `Mono`. Beaucoup d'opérateurs continuent sur le thread qui reçoit le signal. Une event loop Netty peut servir de nombreuses connexions ; une opération bloquante longue l'empêche de faire avancer ces autres connexions.

Dans le code métier, éviter `block()`, `Future.get()`, `join()`, `Thread.sleep()` et les pilotes de base synchrones. Un `map` qui lance JDBC ou attend un fichier bloque toujours, même s'il se trouve dans une jolie chaîne de `Mono`.

Le pilote Mongo réactif et `WebClient` savent attendre leurs I/O sans immobiliser l'event loop. Leur ajouter partout `subscribeOn(boundedElastic())` ajoute des changements de thread sans corriger un problème réel.

Kafka présente une frontière différente : `send()` retourne une future, mais son invocation peut attendre les métadonnées ou une place dans le buffer. L'adaptateur fait donc :

```java
return Mono.fromCallable(() -> template.send(topic, key, json)) // Invocation différée.
    .subscribeOn(Schedulers.boundedElastic())                  // Isole sa partie bloquante.
    .flatMap(future -> Mono.fromFuture(future, true))           // Attend l'ack sans get().
    .then();                                                  // Contrat : achèvement seul.
```

`subscribeOn` choisit où souscrire/exécuter la source. `publishOn` déplace le traitement des signaux en aval. Ils ne sont pas interchangeables et n'accélèrent pas automatiquement une chaîne. `boundedElastic` borne les ressources, mais ne remplace pas les limites métier de concurrence ni les budgets d'attente.

Sources : [schedulering Reactor](https://projectreactor.io/docs/core/release/reference/coreFeatures/schedulers.html), [contrat de `KafkaProducer.send`](https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html).

## 10. Reactor Context et transaction

Dans une application impérative, certaines ressources de transaction sont associées au thread. Une chaîne réactive peut changer de thread pendant son exécution. Le contexte utile doit donc suivre l'abonnement.

Le `ReactiveMongoTransactionManager` utilise le contexte réactif pour associer la session Mongo aux opérations de la même chaîne. `MongoUnitOfWork` enveloppe le travail avec `TransactionalOperator` et retourne ce publisher. Il ne souscrit pas lui-même.

Un `subscribeOn` bien composé ne supprime pas par lui-même le `Context`. En revanche, créer une souscription indépendante avec `subscribe()` à l'intérieur d'une transaction détache le travail de la séquence qui gère sa durée et son résultat.

Pour expérimenter le mécanisme sans base :

```java
Mono<String> diagnostic = Mono.deferContextual(context ->
    Mono.just("requête=" + context.get("requestId")))
    .contextWrite(context -> context.put("requestId", "demo-42"));
```

Le contexte est associé au subscriber et remonte lors de la souscription. Il est immuable : `contextWrite` construit une vue enrichie pour les opérateurs concernés. Ce n'est pas un dictionnaire global à modifier depuis plusieurs requêtes. Le projet utilise l'identifiant WebFlux pour ses réponses et logs HTTP ; il ne prétend pas fournir déjà une propagation distribuée complète de traces jusqu'à Kafka.

Sources : [Reactor Context](https://projectreactor.io/docs/core/release/reference/advancedFeatures/context.html), [transactions réactives Mongo](https://docs.spring.io/spring-data/mongodb/reference/mongodb/client-session-transactions.html).

## 11. Annulation : arrêter l'attente ne remonte pas le temps

Un navigateur peut se déconnecter. `take(...)`, `timeout(...)` ou un test peuvent aussi annuler un abonnement. Les opérateurs réactifs propagent cette annulation vers l'amont pour libérer les ressources qu'ils contrôlent.

Pour une transaction encore active, `TransactionalOperator` traite l'annulation comme une raison de rollback. Mais un timeout reçu par le client ne prouve pas qu'une écriture distante n'a jamais été validée : le commit peut avoir précédé la perte de la réponse. La clé d'idempotence sert justement à reprendre cette situation sans créer une deuxième commande.

Dans l'adaptateur Kafka, `Mono.fromFuture(future, true)` supprime la propagation de cancellation vers la future. Un envoi déjà transmis au broker ne peut pas être révoqué en annulant une attente locale. Le relay conserve donc l'hypothèse d'un possible doublon, même après un timeout.

La cancellation n'est pas un événement métier `ORDER_CANCELLED`. Fermer un onglet annule l'intérêt du client pour une réponse ; annuler une commande est une action explicite de l'API qui enregistre un nouvel état et un événement.

Source : [signaux d'annulation et transactions Spring](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html#tx-prog-operator-cancel).

## 12. Timeouts, retries et erreurs

Le client catalogue applique un timeout de **2 secondes par tentative**, puis au maximum **2 retries** pour des erreurs temporaires sélectionnées. Cela peut produire trois requêtes HTTP. Le budget total dépasse donc 2 secondes : il inclut les tentatives et leurs backoffs. Le pool HTTP ajoute aussi des bornes de connexions et de demandes en attente dans `ApplicationConfiguration`.

Un retry réabonne à la source. Il ne reprend pas magiquement au milieu de la lambda qui a échoué. C'est une raison de construire la source de manière paresseuse et de vérifier l'idempotence de ses effets.

Le laboratoire réessaie les erreurs réseau, les timeouts et certains échecs serveur du **GET** catalogue. Il ne réessaie pas la référence inconnue 404. La création de commande n'est pas enveloppée dans un retry global aveugle : le protocole de reprise repose sur la clé fournie par le client et la transaction Mongo.

`doOnError` observe une erreur ; il ne la traite pas. `onErrorMap` la traduit ; il ne la transforme pas en succès. `onErrorResume` change le chemin d'exécution : l'utiliser seulement lorsque le nouveau chemin possède une signification métier claire. Un `.onErrorResume(error -> Mono.empty())` placé en fin de listener ferait croire à Kafka que le traitement a réussi.

## 13. Backpressure et bornes de mémoire

Reactive Streams permet à un abonné de demander une quantité d'éléments. Cela évite qu'une source coopérative émette arbitrairement plus vite que son consommateur. Cette demande ne signifie pas que la base, le réseau, les buffers du système et le navigateur n'alloueront aucune mémoire intermédiaire.

La pagination de `OrderService.list` impose `1 ≤ size ≤ 50` et `0 ≤ page ≤ 1000`. L'adaptateur limite la requête Mongo, puis le service collecte au maximum `size + 1` éléments. L'élément supplémentaire détermine `hasNext`. `collectList` est ici justifié parce que la collection est bornée ; l'appliquer à un flux infini ou à toute la base ne le serait pas.

Dans `EventsController` :

```java
Flux.interval(Duration.ZERO, Duration.ofSeconds(1))
    .onBackpressureDrop()
    .concatMap(tick -> audit.recent(20).collectList(), 1);
```

Le timer ne ralentit pas l'horloge. Quand l'aval ne demande plus de tick, `onBackpressureDrop` peut abandonner des **ticks de polling**. Cela ne supprime aucun document d'audit. `concatMap(..., 1)` limite la prélecture et ne lance qu'une lecture Mongo à la fois pour cette connexion.

Source : [backpressure dans WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux/new-framework.html).

## 14. Ce que le SSE garantit réellement

`/api/events` envoie chaque seconde un snapshot des **20 audits les plus récents**. La connexion se termine au bout de 10 minutes ; `EventSource` peut se reconnecter et récupérer un nouveau snapshot. Le flux ne contient pas d'identifiant SSE durable ni de traitement de `Last-Event-ID`.

Ce comportement restaure un état récent après reconnexion. Il ne garantit pas que le navigateur verra tous les événements intermédiaires : plus de 20 événements peuvent survenir entre deux snapshots. L'historique durable se trouve dans Mongo, pas dans la connexion HTTP.

`EventSource` n'envoie pas des demandes Reactive Streams `request(n)`. La régulation des écritures serveur et du transport est différente de ce protocole Java. Il faut donc expliquer séparément la backpressure dans les opérateurs, les bornes de collecte et le contrat fonctionnel du navigateur.

Chaque connexion SSE réalise ses propres lectures. Avec 1 000 connexions, une seconde d'intervalle peut représenter environ 1 000 recherches Mongo par seconde, avant effets de ralentissement ou de déconnexion. C'est un calcul de dimensionnement du code, pas une mesure de performance. Une version à grande échelle devrait choisir un mécanisme de diffusion et une stratégie de reprise adaptés.

## 15. Tester les signaux au lieu de dormir

Ouvre [ReactorLearningTest](../src/test/java/fr/wael/reactiveorders/learning/ReactorLearningTest.java). Les scénarios rendent visibles la paresse, la demande, l'ordre, les retries, les erreurs et l'annulation.

`StepVerifier.create(publisher, 0)` commence sans demande. `thenRequest(1)` demande un élément. `expectNext(...)` observe la valeur attendue. `thenCancel()` vérifie un scénario où l'abonné arrête son intérêt.

`StepVerifier.withVirtualTime(() -> publisher)` installe une horloge de test pour les opérateurs temporels. Construire le publisher **dans le supplier** permet à ces opérateurs d'utiliser l'horloge virtuelle. Cette technique accélère un test de backoff ; elle ne remplace pas un vrai broker pour vérifier la livraison Kafka.

Les tests de cas d'usage, les tests WebClient, les intégrations Mongo/Kafka et les tests navigateur démontrent des propriétés différentes. Une suite de mocks verte ne prouve pas le fonctionnement d'une transaction Mongo. Source : [tests Reactor](https://projectreactor.io/docs/core/release/reference/testing.html).

## 16. Quand choisir autre chose que WebFlux

WebFlux convient à ce laboratoire parce que les I/O Mongo et HTTP possèdent des pilotes réactifs et que le suivi SSE rend les flux visibles. Ce n'est pas la preuve qu'il surpasse toujours Spring MVC.

| Situation | Question de choix |
|---|---|
| I/O réactives, nombreuses connexions, flux continus | WebFlux permet une composition cohérente de bout en bout |
| Application surtout JDBC/JPA et bibliothèques bloquantes | MVC peut conserver un modèle beaucoup plus simple |
| Projet impératif avec Java 21 et threads virtuels | Évaluer cette option avant de réécrire tous les cas d'usage en Reactor |
| Calcul lourd limité par le CPU | Changer le modèle d'attente ne crée pas de capacité CPU supplémentaire |
| Équipe sans expérience Reactor | Inclure le coût de formation, de diagnostic et d'exploitation |

Les threads virtuels réduisent le coût de nombreux threads qui attendent ; ils ne rendent pas infinies les connexions Mongo, les requêtes HTTP ou la mémoire. Ils ne fournissent pas non plus à eux seuls le protocole de demande de Reactive Streams. Choisir avec des mesures représentatives du trafic, du pool de dépendances et des compétences de l'équipe. Voir [comparaison des modèles dans Spring](https://docs.spring.io/spring-framework/reference/web/webflux/new-framework.html) et [présentation officielle des threads virtuels Java](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html).

## 17. Exercices de compréhension

Avant de modifier le projet, répondre avec une phrase et pointer l'opérateur concerné :

1. Pourquoi `switchIfEmpty` ne masque-t-il pas une panne Mongo ?
2. Pourquoi le GET catalogue se trouve-t-il hors transaction ?
3. Que ferait une seconde souscription au même `Mono` cold ?
4. Pourquoi `thenReturn(saved)` propage-t-il encore une erreur d'outbox ?
5. Que protège réellement `boundedElastic` dans le publisher Kafka ?
6. Un timeout Kafka prouve-t-il que le broker n'a rien reçu ?
7. Que perd-on en abandonnant un tick SSE ? Et que conserve Mongo ?
8. Pourquoi une chaîne réactive sans `block()` peut-elle malgré tout saturer un système ?

Pour vérifier tes réponses, commence par les tests d'apprentissage, puis suis `OrderService.create` et `OutboxRelay.deliver` dans le debugger. L'étape suivante est le [chapitre fiabilité Mongo/Kafka](03-fiabilite-mongo-kafka.md), qui distingue clairement les garanties de la transaction, du broker et du consommateur.
