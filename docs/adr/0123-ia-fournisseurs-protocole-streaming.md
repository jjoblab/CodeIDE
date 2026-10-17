# ADR 0123 — IA : fournisseurs, protocole HTTP et streaming

- **Statut** : accepté (propriétaire, 2026-10-18 — « GO lot IA-0 »)
- **Date** : 2026-10-17
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0121, ADR 0122. **Cohérent avec** : ADR 0033 (client `HttpURLConnection`,
  « aucune dépendance réseau tierce »), règle 9 (jamais deviner un numéro de version).
- **Contexte** :
  - Il faut du **streaming** (réponse affichée au fil de l'eau) et des **appels d'outils**
    (agent). Le seul code réseau existant (`GestionnaireTelechargement`,
    `ClientManifesteOutils`, `ClientMavenHttp`) fait des requêtes simples, sans flux.
  - Le catalogue de versions ne contient ni OkHttp, ni Ktor, ni SDK de fournisseur.
  - Les identifiants de modèles changent régulièrement : les figer dans le code
    créerait une dette immédiate.

## Décision

1. **Abstraction `AiProvider`** (dans `:core:ai`) :
   `fun stream(request: AiRequest): Flow<AiEvent>`, plus `suspend fun listModels(): AppResult<List<AiModelInfo>>`
   et `suspend fun ping(): AppResult<Unit>` (test de connexion).
2. **Deux adaptateurs natifs au lancement** (`:ai:providers`) :
   - **Messages Anthropic** (`POST /v1/messages`, flux SSE, blocs `tool_use`) ;
   - **Chat Completions « compatible OpenAI »** (`POST /v1/chat/completions`, flux SSE,
     `tool_calls`) — couvre OpenAI, les agrégateurs, et les serveurs locaux
     (boucle locale, ADR 0121 point 5). URL de base **configurable**.
3. **Événements normalisés** : `AiEvent` = `TextDelta`, `ToolCallStarted`, `ToolCallArguments`
   (fragments), `ToolCallCompleted`, `Usage(inputTokens, outputTokens)`, `Done(stopReason)`,
   `Failure(AppError)`. L'UI et l'agent ne voient jamais le format du fournisseur.
4. **Transport** : `AiHttpTransport` au-dessus de `HttpURLConnection`, lecture ligne à ligne
   du flux dans un `Dispatchers.IO` **injecté** (`DispatcherProvider`), annulation
   coopérative (fermeture de la connexion à l'annulation du `Flow`),
   `CancellationException` toujours relancée.
5. **Erreurs** modélisées (`AppError`) : clé absente/invalide (401/403), quota/limite
   de débit (429, `Retry-After` respecté), indisponibilité (5xx), hors-ligne, délai dépassé,
   réponse mal formée. Reprise avec backoff borné **uniquement** pour 429/5xx **avant**
   le premier jeton reçu ; jamais de reprise silencieuse d'un flux commencé.
6. **Identifiants de modèles non figés** : la liste vient de `listModels()` (bouton
   « Actualiser ») ; l'utilisateur peut saisir un identifiant libre. Le code ne
   contient **aucun** nom de modèle par défaut.
7. **Sérialisation** : `kotlinx-serialization-json` déjà au catalogue (1.9.0).

## Options considérées

### Option A — `HttpURLConnection` + parseur SSE maison + 2 adaptateurs (retenue)
| Dimension | Évaluation |
|---|---|
| Complexité | Moyenne (≈ 400 lignes de transport/SSE, bien testables) |
| Coût | 0 dépendance, 0 Mo d'APK supplémentaire |
| Scalabilité | Un adaptateur de plus = une classe |
| Familiarité | Élevée (même client que le bootstrap) |

Tests réseau **sans dépendance** : serveur local `com.sun.net.httpserver` (JDK) dans les
tests JVM, rejouant des flux SSE enregistrés (fichiers dorés dans `src/test/resources`).

### Option B — OkHttp (+ okhttp-sse)
Meilleur confort (pool, HTTP/2, SSE), mais première dépendance réseau tierce :
audit de licence, taille, mise à jour. Si retenue plus tard, **vérifier la version sur
Maven Central** (règle 9) et amender cette ADR. **Écartée au lancement.**

### Option C — SDK officiels des fournisseurs
Couplage fort à un fournisseur, tailles importantes, exigences de versions Kotlin/JVM
non vérifiées avec Kotlin 2.2.10 + AGP 9.4.1. **Écartée.**

## Analyse des compromis
A maximise le contrôle et minimise la surface de dépendances au prix de code à tester
soigneusement (flux tronqués, lignes `data:` multiples, `event:` inconnus, UTF-8 coupé
en milieu de séquence). Les fichiers dorés couvrent ce risque.

## Conséquences
- **Plus facile** : aucun conflit de dépendances ; comportement identique en test et en prod.
- **Plus difficile** : suivre les évolutions d'API des fournisseurs (adaptateurs à faire vivre).
- **À revoir** : passage à OkHttp si HTTP/2 ou le pool de connexions deviennent nécessaires.

## Actions
1. [ ] Définir `AiProvider`, `AiRequest`, `AiEvent`, `AiModelInfo` dans `:core:ai` (lot IA-1).
2. [ ] Implémenter `AiHttpTransport` + `SseParser` + 2 adaptateurs, avec fichiers dorés (lot IA-1).
3. [ ] Test d'annulation : annuler le `Flow` ferme la socket en < 1 s (lot IA-1).
4. [ ] Test « aucun nom de modèle codé en dur » (grep dans le test d'architecture) (lot IA-9).
