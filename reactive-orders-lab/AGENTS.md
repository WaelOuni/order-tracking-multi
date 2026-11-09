# Instructions pour travailler sur cet atelier

Ce projet enseigne la programmation réactive à un développeur Java. Garder les explications et commentaires pédagogiques en français.

- Lire README.md et docs/07-validation.md avant de modifier le projet.
- Domaine sans Spring/Reactor/Mongo/Kafka ; application dépend de ports, pas d'adaptateurs.
- Aucun block/get/join/subscribe manuel dans les chemins métier. Isoler les APIs réellement bloquantes à leur frontière ; ne pas déplacer toutes les I/O sur boundedElastic.
- Conserver la transaction commande + outbox, les tokens de lease, les IDs d'événement stables et l'audit idempotent.
- Ne jamais déclarer une garantie exactly-once de bout en bout, ni supposer l'ordre des effets d'un KafkaListener retournant Mono.
- Borner les requêtes, buffers, timeouts, retries et niveaux de concurrence. Ne pas réessayer aveuglément un effet non idempotent.
- Documenter le pourquoi des nouveaux opérateurs/dépendances dans le code et le guide concerné.
- Exécuter ./mvnw verify pour un changement Java. Pour une modification d'infrastructure/persistance/Kafka, exécuter aussi ./mvnw verify -Pintegration avec Docker et signaler explicitement tout blocage.
- Tester l'UI avec npm run test:ui ; utiliser npm run test:live quand la stack réelle est disponible.
- Les tests mocks et la compilation ne remplacent pas des tests réels Mongo/Kafka/navigateur. Ne pas transformer une impossibilité d'exécution en succès ou skip silencieux.
- Le dépôt parent contient un autre projet : limiter les changements à reactive-orders-lab/ et à son workflow dédié.
