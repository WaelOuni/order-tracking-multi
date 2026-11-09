# Architecture, DDD et choix des dépendances

## 1. Le périmètre métier et l'agrégat

Une commande porte une identité, une référence client fictive, un produit, une quantité, un prix capturé à la création et un état `CREATED` ou `CANCELLED`. Son total est calculé en centimes. Le catalogue peut changer son prix demain : le montant de la commande déjà enregistrée reste le même.

[`Order`](../src/main/java/fr/wael/reactiveorders/domain/Order.java) est l'agrégat. Son constructeur interdit les états invalides ; passer par HTTP n'est pas nécessaire pour activer ces règles. `cancel()` retourne un nouvel objet, sauf si l'annulation a déjà eu lieu. Aucune méthode métier ne publie elle-même dans Kafka.

`Product` est un snapshot du catalogue, pas une entité de stock dont cette application serait propriétaire. `OrderEvent` est un contrat d'événement de schéma 1, avec son identité propre et la version d'agrégat. `DomainRuleException` ne connaît aucun statut HTTP.

Le DDD ne signifie pas ajouter le mot Aggregate à chaque classe. Ici, les choix utiles sont : identité stable, frontière transactionnelle claire, invariants locaux, langage métier explicite et indépendance vis-à-vis du stockage. Un seul contexte métier commandes et un audit technique suffisent. La réserve de stock, le paiement, les expéditions et la facturation ne sont pas simulés comme des garanties réelles.

## 2. Direction des dépendances

| Couche | Peut connaître | Ne doit pas connaître |
| --- | --- | --- |
| Domaine | Java standard, ses règles et ses valeurs | Reactor, Spring, Mongo, Kafka, HTTP |
| Application | Domaine, ports, Mono/Flux, Clock | Classes concrètes des adaptateurs |
| Adaptateurs | Domaine/application et technologie de leur frontière | Responsabilités métier d'un autre adaptateur |
| Configuration | L'ensemble pour l'assemblage | Nouvelle logique métier |

L'application choisit d'exposer Reactor dans ses ports : `Mono<Order>` exprime une opération asynchrone produisant zéro ou une commande. C'est un compromis explicite. Un domaine complètement indépendant du moteur réactif reste possible, tout en acceptant que l'orchestration dépende de Reactor. Mettre `Mono` dans chaque value object n'apporterait rien.

Les beans de cas d'usage sont créés dans `ApplicationConfiguration`. `OrderService` reste une classe Java ordinaire, construisible sans contexte Spring dans les tests. ArchUnit vérifie automatiquement l'indépendance du domaine et la direction application → ports.

## 3. Pourquoi un monolithe modulaire

Distribuer chaque fonction dans un microservice ajouterait discovery, authentification interservices, déploiements multiples et synchronisation de versions avant même d'apprendre `flatMap`. Un seul service permet de suivre un appel bout en bout dans le debugger. Mongo, Kafka et le catalogue externe suffisent déjà à montrer les erreurs et la cohérence différée.

La séparation est logique, dans les packages et les ports. Le nombre de processus peut évoluer après avoir défini des besoins de déploiement ou de scaling indépendants. Ce lab n'offre pas une isolation physique des modules ; les tests d'architecture empêchent les principaux couplages accidentels.

## 4. Lire le pom.xml

Le parent **Spring Boot 4.1.2** centralise les versions compatibles de Spring, Reactor, Netty, Jackson, Mongo et Kafka. On ne copie pas des versions de bibliothèques tirées d'articles de dates différentes. Pour voir les versions réellement résolues :

```bash
./mvnw dependency:tree
./mvnw help:effective-pom
```

| Dépendance | Rôle précis | Pourquoi ce choix |
| --- | --- | --- |
| `spring-boot-starter-webflux` | REST, SSE, serveur Reactor/Netty | Serveur HTTP avec I/O non bloquantes |
| `spring-boot-starter-webclient` | Auto-configuration du WebClient.Builder | Boot 4 sépare le client du serveur ; conserve les customizers et l'observation configurés par Boot |
| `spring-boot-starter-data-mongodb-reactive` | Template Mongo réactif et mapping | Composer les opérations sans pilote bloquant |
| `spring-boot-starter-kafka` | Auto-configuration Boot 4, template, listeners | Intégration maintenue ; le pont Reactor est visible dans le code |
| `spring-boot-starter-validation` | Bean Validation à l'entrée HTTP | Rejeter tôt un payload incorrect ; complète les invariants métier |
| `spring-boot-starter-actuator` | Health et métriques Micrometer | Observer le service sans inventer une route de santé ad hoc |
| `spring-boot-starter-test` | JUnit, Mockito, AssertJ | Tester le métier et les adaptateurs sans framework de test supplémentaire |
| `spring-boot-starter-webflux-test` | WebTestClient et support de tests WebFlux | Ne pas confondre tests MVC/MockMvc et WebFlux |
| `reactor-test` | StepVerifier et temps virtuel | Vérifier signaux, demande, erreurs et annulation |
| `archunit-junit5` | Contraintes de dépendances | Vérifier les règles d'architecture dans le build |
| `testcontainers-mongodb` / `testcontainers-kafka` / `testcontainers-junit-jupiter` | Infrastructure réelle de test | Vérifier transactions, index, leases et comportement broker |
| `mockwebserver` | Un petit vrai serveur HTTP de test | Observer les requêtes WebClient, statuts et retries sans dépendre d'un service distant |
| `@playwright/test` côté Node | Navigateur Chromium, assertions UI | Tester l'expérience utilisateur et un parcours réel complet |

### Ce qui n'est pas ajouté

Pas de JPA, JDBC ou pilote Mongo synchrone : ils demanderaient une frontière bloquante supplémentaire. Pas de Spring MVC : mélanger les deux starters rend le choix du serveur moins évident. Pas de Lombok : les champs, constructeurs et transformations restent visibles. Pas de Resilience4j uniquement pour effectuer deux retries : Reactor suffit ici ; un circuit breaker serait un exercice motivé par une panne prolongée.

Pas de Reactor Kafka : sa maintenance a été arrêtée. Cela ne signifie pas que Reactor Core ou WebFlux sont abandonnés. Le pont `KafkaTemplate` est une adaptation bornée, pas la prétention que l'API Kafka est entièrement Reactive Streams.

Pas de Cucumber par défaut : les scénarios Given/When/Then JUnit sont exécutables et lisibles sans maintenir un second langage et ses step definitions. Si un Product Owner doit collaborer sur un langage métier partagé, ajouter Cucumber peut devenir pertinent.

### Plugins de build

Le plugin Spring Boot génère le JAR exécutable. Surefire lance les `*Test`. Failsafe lance les `*IT` dans le profil `integration`, avec vérification en phase `verify`. JaCoCo instrumente les tests et produit un rapport ; il ne justifie pas des tests qui ne vérifieraient que des getters.

Java 21 rend l'attachement dynamique des agents moins souhaitable. Mockito est chargé explicitement avec `-javaagent`, dont le chemin vient de `maven-dependency-plugin:properties`. `@{argLine}` préserve simultanément l'agent JaCoCo. Cette configuration appartient aux JVM de tests, pas au serveur livré.

## 5. DTO, document et domaine sont trois contrats

`CreateOrderRequest` décrit l'entrée HTTP. `OrderResponse` rend volontairement visibles les champs API, dont `totalCents`. `OrderDocument` décrit Mongo, avec `_id`, index et `@Version`. `Order` décrit le métier.

Une seule classe annotée à la fois pour Mongo, JSON, validation et règles métier serait plus courte mais couplerait les évolutions. Ici, changer un index ne modifie pas l'API. Ajouter un champ interne de persistance ne l'expose pas accidentellement au navigateur. Le coût est quelques lignes de mapping explicites et testables.

`StoredEvent` stocke les UUID en chaînes pour ne pas dépendre d'une représentation BSON UUID implicite. Les dates métier sont normalisées à la milliseconde, précision de BSON Date. Cette précision fait partie du contrat, notamment pour comparer les événements lors d'une redélivrance.

## 6. Observabilité et limites des mesures

Actuator expose `health` et `info`. Le relay incrémente `orders.outbox.published` après les publications reconnues comme terminées et `orders.outbox.poll.errors` lors d'une erreur de polling. Cela ne remplace pas une mesure du backlog, de son âge, des FAILED et de la DLT.

Chaque réponse HTTP inclut `X-Request-Id`. Les erreurs exposent cet identifiant dans le champ `traceId` pour retrouver une requête locale. Il ne s'agit **pas encore d'une trace distribuée OpenTelemetry** propagée jusqu'à Kafka.

Avant une exploitation réelle : mesurer le nombre de connexions, le pool catalogue, les temps de réponse, le backlog outbox, les consumer lags et le taux de DLT ; définir des objectifs et des alertes. Ajouter une dépendance d'observabilité doit répondre à cette question concrète, pas simplement allonger la stack.

## Sources

- [Versions gérées par Boot](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)
- [Modèle WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux/new-framework.html)
- [Optimistic locking Mongo](https://docs.spring.io/spring-data/mongodb/reference/mongodb/template-crud-operations.html#mongo-template.optimistic-locking)
- [BSON Date et sa précision](https://www.mongodb.com/docs/manual/reference/bson-types/#date)
- [Annonce d'arrêt Reactor Kafka](https://spring.io/blog/2025/05/20/reactor-kafka-discontinued/)
- [Mockito et instrumentation Java](https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html)
