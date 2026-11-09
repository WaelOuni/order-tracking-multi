# 05 — L’interface, les flux et les tests navigateur

L’interface permet de **voir les effets du backend réactif** : une commande est d’abord enregistrée, puis son événement apparaît après son passage dans l’outbox, Kafka et le consommateur d’audit. Elle sert aussi à examiner les situations moins visibles dans une démonstration heureuse : réponse perdue, catalogue indisponible, lecture annulée et reconnexion.

## 1. Où lire le code

| Fichier | Ce qu’il explique |
|---|---|
| [`index.html`](../src/main/resources/static/index.html) | Structure sémantique, formulaires, états de chargement, templates inertes |
| [`styles.css`](../src/main/resources/static/styles.css) | Mise en page responsive, focus clavier, mouvement réduit |
| [`app.js`](../src/main/resources/static/app.js) | `fetch`, délai maximal, idempotence, pagination, `EventSource`, nettoyage |
| [`EventsController.java`](../src/main/java/fr/wael/reactiveorders/adapter/in/web/EventsController.java) | Production du `Flux` de snapshots SSE côté serveur |
| [`orders.spec.js`](../ui-tests/orders.spec.js) | Tests navigateur avec API contrôlées |
| [`live.spec.js`](../ui-tests/live.spec.js) | Parcours navigateur sur la vraie application |
| [`playwright.config.js`](../playwright.config.js) | Séparation des modes isolé et réel, délais et rapports |

Il n’y a pas de compilation frontend. Spring sert directement le HTML, le CSS et le module JavaScript présents dans `src/main/resources/static/`. Node est nécessaire pour les tests, pas pour exécuter l’interface livrée dans le JAR.

## 2. « Réactif » ne désigne pas la même chose partout

Dans le navigateur, un événement utilisateur déclenche une requête ; une promesse ou un événement SSE déclenche ensuite une mise à jour du DOM. Le JavaScript est asynchrone et événementiel. Il n’exécute ni `Mono` ni `Flux`.

Dans le backend, WebFlux et Reactor composent des `Publisher` compatibles Reactive Streams. Une souscription transporte les valeurs, les erreurs, la complétion et la demande. La demande permet de coordonner la production entre composants qui la comprennent. Voir les [expériences exécutables Reactor](../src/test/java/fr/wael/reactiveorders/learning/ReactorLearningTest.java).

**Une connexion SSE n’expose pas `request(n)` au JavaScript.** Afficher dix lignes au lieu de vingt dans le DOM ne demande pas automatiquement à Kafka de produire deux fois moins. Les limites doivent exister à chaque endroit pertinent : requêtes Mongo bornées, concurrence contrôlée dans le relay, cadence du polling SSE et taille de l’aperçu navigateur. La [spécification EventSource](https://html.spec.whatwg.org/multipage/server-sent-events.html) définit les messages et la reconnexion ; elle ne définit pas un protocole Reactive Streams.

Le laboratoire conserve donc des limites explicites. Il ne prétend pas fournir une backpressure uniforme de la souris jusqu’au broker.

## 3. Comprendre une création de commande

Dans `createOrder`, lis les étapes dans cet ordre :

1. `preventDefault()` garde la page ouverte ; `reportValidity()` vérifie les contraintes HTML.
2. `formPayload()` construit les données métier et retire les espaces aux extrémités de la référence client.
3. La signature du contenu permet de savoir si l’utilisateur répète la même intention.
4. Une clé UUID est créée uniquement pour une nouvelle intention.
5. `setCreating(true)` désactive le formulaire et annonce la progression.
6. `request()` envoie le POST et attend sa réponse sans immobiliser le thread d’interface.
7. Un succès remet la pagination sur la première page et relit l’état enregistré.
8. Le `finally` réactive le formulaire, même en cas d’échec.

Le montant indiqué avant envoi est une **estimation locale** destinée à rendre le formulaire lisible. Le backend récupère le produit auprès du catalogue et recalcule le total. Les données envoyées ne contiennent aucun prix.

La notification « enregistrée » confirme le succès HTTP. Elle ne prétend pas que Kafka a déjà traité l’événement. L’audit apparaît dans la partie inférieure lorsqu’il a été consommé et enregistré.

### Pourquoi vérifier `response.ok`

Une réponse HTTP 409 ou 503 reste une réponse HTTP reçue. Le helper lit son `ProblemDetail` et transforme ce résultat en `ApiError` pour l’interface. Une erreur réseau, une annulation volontaire et une réponse illisible demandent des traitements différents. Le [standard Fetch](https://fetch.spec.whatwg.org/) décrit séparément les réponses et les erreurs réseau.

Les messages venant du serveur sont affectés à `textContent`. Un détail contenant du HTML sera montré comme du texte ; il ne devient pas une balise active. La validation côté navigateur améliore l’expérience, mais les invariants restent contrôlés côté API et domaine.

## 4. Un timeout ne veut pas dire « rien ne s’est passé »

`request()` impose **15 secondes** au maximum à l’attente navigateur, lecture du corps comprise. Le helper crée son propre `AbortController`, propage l’annulation du demandeur, puis nettoie son timer et son écouteur dans un `finally`.

Le cas à comprendre est celui-ci :

1. Le navigateur envoie une création avec la clé `K`.
2. Mongo confirme la transaction commande + outbox.
3. La réponse HTTP est perdue ou arrive après le délai navigateur.
4. Le navigateur voit une erreur ; la commande existe pourtant déjà.

**Arrêter d’attendre ne constitue pas un rollback distribué.** C’est pourquoi l’interface conserve `K` lorsque la même demande est réessayée. Dans [`OrderService.java`](../src/main/java/fr/wael/reactiveorders/application/OrderService.java), la même clé et le même contenu renvoient la commande existante. Une même clé réutilisée avec un autre contenu provoque un conflit. L’unicité Mongo protège aussi la course entre deux créations concurrentes.

| Situation côté interface | Gestion de la clé |
|---|---|
| Réponse perdue, réseau coupé ou délai dépassé | Conserver la clé pour réessayer le même contenu |
| Modification du produit, de la quantité ou de la référence | Nouvelle intention : nouvelle clé |
| Réponse de création réussie | Fin de l’intention ; la prochaine création reçoit une autre clé |
| Rechargement complet de la page | L’état mémoire est perdu |

Cette dernière limite est volontairement visible : le laboratoire ne stocke pas la clé dans `sessionStorage`. Après un résultat incertain, **ne recharge pas la page avant de réessayer avec les mêmes informations**. En production, une stratégie persistante de reprise doit préciser sa durée, sa confidentialité et la façon de vérifier l’opération avant de créer une nouvelle intention.

L’annulation métier est elle aussi idempotente côté service. Une commande déjà annulée est renvoyée sans produire une seconde transition. Le timeout navigateur n’est pas la stratégie de timeout du serveur : le client catalogue Java possède ses propres limites et retries ciblés.

## 5. Annuler une lecture obsolète

`loadOrders()` utilise deux protections complémentaires :

- `ordersController.abort()` arrête la requête précédente lorsqu’une nouvelle lecture la remplace.
- `requestSequence` empêche une ancienne réponse de modifier l’écran après une réponse plus récente.

L’annulation réduit le travail inutile lorsqu’elle peut se propager. Le compteur protège la cohérence du rendu même si une réponse termine presque au même moment. Il ne faut pas supposer qu’un `abort()` peut remonter le temps et supprimer un travail déjà achevé.

`loadingOrders` désactive la pagination pendant une lecture, y compris lorsqu’un autre traitement demande un rendu. En cas de panne, les anciennes lignes peuvent rester visibles avec une erreur explicite. `loadedPage` empêche d’afficher « Page 2 » au-dessus des lignes provenant réellement de la page 1.

Une liste vide confirmée et une lecture échouée ne sont pas le même état. L’interface ne présente donc pas le message de liste vide après une erreur initiale.

## 6. Pagination : borne utile, photographie non figée

L’interface demande dix commandes à la fois. Le service récupère au plus `size + 1` commandes pour calculer `hasNext`, sans effectuer de `COUNT` global. Mongo trie par `createdAt` décroissant, puis `_id` croissant : cette seconde clé départage deux dates égales.

La pagination utilise néanmoins `skip`/`limit`. Elle n’est pas une photographie immuable de toute la collection. Si une nouvelle commande est ajoutée en tête entre la lecture de deux pages, les positions se décalent : une ligne déjà vue peut réapparaître. Une suppression pourrait provoquer un saut. Un tri déterministe ne supprime pas ce phénomène.

Le serveur borne les valeurs de `page` et `size`, ce qui limite l’utilisation du laboratoire. Pour un historique volumineux ou parcouru pendant de nombreuses écritures, l’exercice 9 propose un curseur composé. La [documentation Mongo sur `skip`](https://www.mongodb.com/docs/manual/reference/method/cursor.skip/) explique aussi pourquoi le coût augmente avec l’offset.

Après une création locale, revenir à la première page rend la nouvelle commande visible. Après un snapshot SSE différent, l’interface relit la page courante ; cela actualise les statuts, sans promettre des positions figées.

## 7. SSE : aperçu vivant et reconnexion

`connectEvents()` crée une seule connexion `EventSource('/api/events')`. Le navigateur reçoit des événements nommés `snapshot`, dont le champ `data` contient un tableau JSON. À l’arrivée d’un nouvel aperçu, `renderEvents()` remplace les lignes existantes. Il ne les ajoute pas systématiquement : une reconnexion ne doit pas dupliquer le même aperçu.

Le contrôleur serveur lit un snapshot initial puis au plus une fois par seconde. Il abandonne les ticks excédentaires avec `onBackpressureDrop()`, effectue une lecture Mongo à la fois avec `concatMap`, et récupère au maximum vingt audits. La connexion est limitée à dix minutes, après quoi le navigateur peut se reconnecter. Le champ SSE `retry` indique un délai de reconnexion de trois secondes.

Le snapshot périodique assure aussi une activité sur la connexion, même sans nouveau métier. Le navigateur compare les identifiants pour éviter de reconstruire inutilement le DOM. Une courte temporisation de 100 ms regroupe les demandes de relecture du tableau.

### Ce flux ne constitue pas un historique complet

Supposons que cinquante événements arrivent pendant une coupure. À la reconnexion, l’aperçu contient seulement les vingt derniers. Les trente précédents ne sont pas nécessairement passés par cette interface. Le laboratoire n’implémente pas de curseur durable de reprise ni de restitution exhaustive à partir de `Last-Event-ID`.

Le tri utilise la date d’enregistrement de l’audit. Le consommateur asynchrone ne garantit pas que tous les effets terminent dans l’ordre des événements métier. **Le statut de commande vient du GET des commandes**, pas d’une reconstitution fragile à partir de l’ordre des lignes de l’aperçu.

La reconnexion restaure donc un aperçu récent ; elle ne prouve ni la consommation de tous les messages, ni une garantie d’« exactement une fois » de bout en bout.

### Fermer proprement

Sur `pagehide`, `cleanUp()` ferme l’`EventSource`, annule les requêtes encore suivies et supprime le rafraîchissement programmé. Une restauration depuis le cache de navigation peut conserver le document sans réexécuter son module : `pageshow` relance alors la lecture et la connexion.

Un abonnement oublié consommerait des connexions et des lectures inutiles. Inversement, fermer une page après avoir envoyé un POST ne garantit pas l’annulation de ce POST côté serveur.

## 8. Accessibilité et sécurité du rendu

Le HTML donne des labels aux champs, des noms aux boutons et une légende au tableau. Le lien d’évitement permet de rejoindre les commandes au clavier. Les états de progression utilisent `aria-busy`, les erreurs `role="alert"`, et les confirmations une zone `role="status"`.

Le journal entier n’est pas une région `aria-live` : relire vingt événements à chaque actualisation serait envahissant. Le badge de connexion annonce l’état du flux. Les mouvements décoratifs respectent `prefers-reduced-motion`, et la couleur des statuts est accompagnée d’un texte.

Les tests contiennent une vérification clavier et une vérification de largeur mobile. Cela **ne constitue pas un audit complet d’accessibilité** : la navigation avec lecteur d’écran, le zoom, les contrastes et les différents navigateurs demandent une vérification complémentaire.

La CSP interdit les scripts/styles inline et les CDN. Les mutations en JSON ne sont pas des POST de formulaire simples cross-origin ; le backend vérifie aussi l’origine. Cette mesure ne remplace ni authentification ni autorisation. Le laboratoire reste destiné à l’exécution locale décrite au chapitre 04.

## 9. Ce que les différentes suites prouvent

| Suite | Réseau/application réelle ? | Ce qu’elle cherche à valider |
|---|---|---|
| `ui-tests/orders.spec.js` | UI et navigateur réels ; réponses API simulées | États d’interface, demandes envoyées, idempotence du client, erreurs, pagination, rendu et lecture SSE |
| `ui-tests/live.spec.js` | UI, API, Mongo et Kafka réels | Parcours création → publication → audit SSE → annulation |
| `FullStackIT.java` | Adaptateurs Java et services Testcontainers réels | Transactions, concurrence, outbox, audit, DLT et contrat SSE sans passer par le navigateur |

Les tests isolés utilisent `page.route()` pour répondre aux demandes réseau, conformément à la [documentation Playwright sur les API simulées](https://playwright.dev/docs/mock). Même lorsqu’un vrai `EventSource` lit leurs frames SSE, ces frames proviennent du test : elles ne prouvent pas que Kafka fonctionne.

La suite comprend **16 scénarios isolés** et **1 scénario live**. Elle teste notamment une commande déjà enregistrée dont la réponse disparaît. Le scénario de timeout avance l’horloge navigateur au lieu d’attendre quinze secondes réelles. Les assertions attendent un état observable ; elles n’emploient pas de pause arbitraire pour espérer que Kafka a terminé.

Ne pas attendre `networkidle` pour décider qu’une page SSE a fini de charger : une connexion persistante fait partie de son fonctionnement. Attendre la ligne, le statut ou l’événement utile rend l’intention du test explicite.

### Exécution

```bash
npm ci
npx playwright install chromium
npm run test:ui

# Application réelle démarrée sur 8080 :
npm run test:live

# Relire les traces et captures conservées lors d’un échec :
npm run test:report
```

`npm run test:live` active son mode par un petit wrapper Node compatible Bash et PowerShell. `PLAYWRIGHT_BASE_URL` permet de changer l’adresse. [`scripts/test-live.sh`](../scripts/test-live.sh) propose également une stack E2E séparée et son nettoyage ; voir le chapitre 04.

**État vérifié pendant la création :** installation npm reproductible, syntaxe JavaScript et découverte des scénarios réussies. Le navigateur Chromium n’était pas disponible et son téléchargement renvoyait une page « Site Unavailable ». Les tests navigateur et le rendu visuel ne sont donc pas annoncés validés dans cet environnement. Une suite collectée n’est pas une suite exécutée ; le dernier rapport CI doit donner le résultat réel.

## 10. Parcours manuel conseillé

1. Démarrer la stack, ouvrir `http://localhost:8080` et les outils réseau du navigateur.
2. Créer `CLIENT-001`, produit `MOUSE`, quantité `2`. Le catalogue du laboratoire donne un total de **59,80 €**. Observer la réponse HTTP, puis l’audit : ce sont deux instants distincts.
3. Annuler la commande. Vérifier le statut confirmé et l’arrivée de l’audit d’annulation.
4. Choisir `OUT_OF_STOCK`. Observer une erreur métier, sans nouvelle ligne de commande réussie.
5. Arrêter temporairement `catalog` sur la stack locale, tenter une création, puis redémarrer `catalog`. Lire le détail renvoyé et les retries du backend.
6. Ouvrir deux onglets. Créer dans l’un et regarder l’actualisation de l’autre.
7. Couper puis rétablir le réseau dans les outils navigateur. Observer le badge de reconnexion et l’aperçu restauré ; ne pas en déduire que tous les événements intermédiaires ont été affichés.
8. Réduire la fenêtre à une largeur mobile, parcourir les contrôles avec Tab, puis activer la préférence de mouvement réduit.

Pour les pannes avec réponse perdue après commit, privilégier les scénarios contrôlés du test : cliquer « hors ligne » à la main ne permet pas de savoir précisément si la transaction a déjà été validée.
