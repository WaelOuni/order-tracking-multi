# Bilan de validation

Bilan vérifié le **9 novembre 2026**. Ce fichier distingue les tests locaux et les preuves d'exécution avec infrastructure réelle.

## Résultat complet : 112 scénarios réussis

Le [run GitHub Actions 42650825121](https://github.com/WaelOuni/order-tracking-multi/actions/runs/42650825121) est **terminé avec succès**. Il valide le code du commit `40bb51a9636261f74a4fc65c13353739317cc368` ; la mise à jour documentaire de ce bilan ne modifie pas ce code.

| Niveau | Scénarios exécutés | Résultat |
| --- | ---: | --- |
| Java : domaine, application, Reactor, contrats REST/HTTP, architecture | 88 | Tous réussis, aucun ignoré |
| Intégration réelle MongoDB 9.0.4 / Kafka 4.3.1 | 7 | Tous réussis, aucun ignoré |
| UI Chromium avec contrats HTTP contrôlés | 16 | Tous réussis, aucun ignoré |
| Navigateur contre la stack Docker réelle | 1 | Réussi, sans interception d'API |
| **Total** | **112** | **0 échec** |

La construction de l'image Docker et le démarrage des quatre services avec sondes de santé réussissent également. Le dernier test suit réellement une création depuis le formulaire, son passage par l'outbox et Kafka, sa réception dans l'audit SSE, puis son annulation et l'événement correspondant. Les rapports Java, JaCoCo et Playwright sont conservés comme artefacts du workflow pendant 14 jours.

## Exécuté avec succès dans l'environnement de création

- Compilation du code principal et des tests avec **JDK Temurin 21.0.13**, **Maven 3.9.11** et **Spring Boot 4.1.2**.
- `mvn verify` : **88 tests, 0 échec, 0 erreur, 0 ignoré**.
- Construction du JAR exécutable et génération du rapport JaCoCo.
- `npm ci`, vérification de syntaxe JavaScript et découverte des suites Playwright.
- Validation syntaxique des fichiers YAML, JSON et des scripts Bash.

| Suite Java exécutée | Tests |
| --- | ---: |
| OrderTest | 12 |
| ProductTest | 9 |
| OrderEventTest | 3 |
| OrderServiceTest | 18 |
| OutboxRelayTest | 8 |
| ReactorLearningTest | 9 |
| ArchitectureTest | 3 |
| OrderControllerTest | 14 |
| WebClientCatalogTest | 7 |
| KafkaEventPublisherTest | 2 |
| OrderEventsListenerTest | 3 |
| **Total** | **88** |

Les tests WebClient utilisent un serveur HTTP local réel, MockWebServer. Les tests du contrôleur lient directement WebTestClient au contrôleur ; ils ne prouvent pas le démarrage de toute l'application. Les tests Kafka unitaires contrôlent la future et la propagation des erreurs avec des doubles de test ; ils ne remplacent pas un broker.

## Suites avec infrastructure réelle

`FullStackIT` comprend **7 scénarios** : REST idempotence/annulation, chaîne outbox→Kafka→audit→SSE, rollback de transaction, version optimiste, concurrence des leases, déduplication concurrente et message poison en DLT.

Ces scénarios nécessitent Docker et sont activés par `./mvnw verify -Pintegration`. Docker n’est pas installé dans l’environnement de création ; ils ont été exécutés sur GitHub Actions. Le profil ne contient pas de mécanisme `disabledWithoutDocker`.

Playwright fournit **16 scénarios UI isolés** et **1 parcours @live** sans interception d'API. Leur lancement local a été bloqué avant les scénarios : Chromium était absent et le téléchargement officiel retournait une page d'indisponibilité au lieu d'une archive. Les 17 scénarios ont ensuite été exécutés avec succès sur GitHub Actions, où Chromium a pu être installé.

## Pipeline exécuté sur GitHub

Le workflow dédié à ce dossier :

1. Configure Java 21 et Node 24.
2. Exécute les 88 tests rapides et les 7 tests d'intégration sur Mongo/Kafka Testcontainers.
3. Installe Chromium et exécute les 16 scénarios UI isolés.
4. Construit la stack Compose réelle et exécute le scénario navigateur complet.
5. Conserve les rapports et les diagnostics, puis arrête les conteneurs.

Consulter le résultat réel du workflow avant d'affirmer que l'ensemble est validé. Un fichier de workflow fourni, une suite compilée ou une découverte de tests ne constitue pas une exécution réussie.

## Contrôles de fiabilité intégrés

- Pas de `block()` dans le code de production, vérifié par ArchUnit.
- Pas d'effet métier avant souscription, testé avec StepVerifier et Mockito.
- La publication attend l'ack Kafka avant le marquage SENT.
- Les retries catalogue ignorent les 404 et les erreurs métier ; les tentatives sont bornées.
- Une clé d'idempotence réutilisée avec un autre contenu est refusée.
- Une panne d'outbox n'est pas convertie en succès HTTP de création.
- Les leases épuisés et les échecs durables ne sont pas supprimés par TTL.
- La précision temporelle est normalisée aux millisecondes avant comparaison BSON.

## Ce que ce bilan ne certifie pas

Ce lab n'a pas fait l'objet d'un test de charge, d'un audit de sécurité, d'un chaos test prolongé, d'un test de cluster Kafka multi-brokers ou d'une qualification de production. Les limites d'authentification, de scaling SSE, de rétention et de reprise opérateur sont décrites dans les guides.


## Premier passage CI et correction d'infrastructure

Le [premier run GitHub](https://github.com/WaelOuni/order-tracking-multi/actions/runs/42647706576) confirme les **88 tests rapides verts**. Le démarrage du conteneur Mongo8.0.26 échoue ensuite à cause de son contrôle de compatibilité du noyau Linux du runner. Cela empêche l'exécution des 7 scénarios réels, et ne constitue pas un échec métier de ces scénarios.

Compose et Testcontainers sont alignés sur MongoDB 9.0.4, qui contient la correction officielle Ubuntu. Le run complet cité en tête de ce bilan vérifie le démarrage de cette version.

Le [deuxième run](https://github.com/WaelOuni/order-tracking-multi/actions/runs/42648657341) confirme le démarrage de MongoDB 9.0.4 et de Kafka. Le contexte complet révèle ensuite l'absence de l'auto-configuration `WebClient.Builder`. Le `pom.xml` inclut désormais le starter **spring-boot-starter-webclient**, distinct du starter serveur WebFlux depuis Boot 4. Les tests d'intégration vérifient cet assemblage ; un builder créé à la main ne remplace pas les customizers du framework.


## Validation Java et navigateur sur le troisième passage

Le [run 42649234567](https://github.com/WaelOuni/order-tracking-multi/actions/runs/42649234567) valide **88 tests rapides et 7 tests d’intégration, sans échec ni test ignoré**. La transaction réelle, les écritures concurrentes, la chaîne Kafka vers l’audit et SSE et le message poison en DLT sont donc exercés avec MongoDB 9.0.4 et Kafka 4.3.1.

Lors de ce troisième passage, **15 scénarios UI passent**, et le test mobile détecte un débordement horizontal. L'analyse identifie les libellés accessibles `.sr-only` du tableau : leur positionnement absolu peut déborder de la page sans bloc de référence dans le conteneur défilant. Le correctif établit ce bloc avec `.table-scroll { position: relative; }`, et borne la piste de grille mobile. Il conserve les libellés, le défilement interne du tableau, l'assertion de largeur et l'assertion de navigation clavier.

Le run final cité en tête de ce bilan valide les **16 scénarios UI**, puis le parcours live complet. Cette progression illustre l'intérêt des différents niveaux de test : les tests de domaine ne peuvent prouver ni l'assemblage Spring, ni la compatibilité d'une image Docker avec le noyau du runner, ni le comportement réel d'une mise en page mobile.
