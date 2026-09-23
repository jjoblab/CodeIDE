# Conventions de code et de documentation

Ce document fixe les conventions du projet CodeIDE. Leur application est
automatisée autant que possible (Spotless, detekt, Lint, revue) ; ce qui suit
en explique le sens et couvre ce que les outils ne voient pas.

## Langues

- **Identifiants du code** (classes, fonctions, variables) : anglais.
- **KDoc, commentaires, commits, documentation, messages d'erreur
  développeur** : français (règle explicite du prompt maître).
- Interface utilisateur : français par défaut (`values/`), anglais
  (`values-en/`). Toute chaîne visible passe par les ressources — jamais en dur.

## Style et formatage

- Style officiel Kotlin appliqué par Spotless + ktlint (`./gradlew
  spotlessApply` pour reformater).
- Indentation 4 espaces ; longueur de ligne maximale 120 (voir
  `.editorconfig`).
- Visibilité `internal` par défaut dans un module ; n'exposer en `public` que
  l'API nécessaire (`explicitApi()` sur `core:model` et `core:domain` force
  la KDoc de l'API publique).
- Une classe = une responsabilité. Fichier > ~300 lignes ou fonction > ~40
  lignes : à découper.
- Injection par constructeur ; pas de singleton statique ; pas d'état global
  mutable.

## KDoc

Obligatoire sur toute classe, interface, objet, fonction et propriété publique
ou `internal` non triviale. Elle décrit le **rôle** (le *pourquoi*, pas la
paraphrase du nom), avec `@param`, `@return`, `@throws` le cas échéant, et pour
une fonction `suspend` le **contexte d'exécution attendu** (dispatcher,
annulation).

```kotlin
/**
 * Vérifie qu'un nom de projet est utilisable sur tous les systèmes de fichiers.
 *
 * Refuse les séparateurs, les noms réservés Windows (les projets peuvent être
 * copiés vers un PC) et les noms vides après trim.
 *
 * @param nom le nom saisi par l'utilisateur.
 * @return `null` si le nom est valide, sinon l'erreur de validation localisable.
 */
fun validerNomProjet(nom: String): ErreurNom?
```

Les commentaires en ligne expliquent le **pourquoi** des choix non évidents
(contournement SAF, ordre des opérations, rollback…), jamais le *quoi* évident.

## Interdictions (règle 4 du prompt maître)

`!!`, `GlobalScope`, `runBlocking` (hors tests), `Thread.sleep`,
`catch (e: Exception) {}` qui avale l'erreur, `@SuppressLint`/`@Suppress` sans
justification commentée. Les erreurs attendues passent par `AppResult` /
`AppError`, pas par des exceptions jusqu'à l'UI. `CancellationException` est
toujours relancée.

## Use cases (étape 1)

Toute logique métier vit dans un **use case** de `core:domain` — jamais dans
une Activity, un Fragment ou un ViewModel (règle 1 du prompt maître). Le
patron imposé :

```kotlin
/**
 * Marque un projet comme ouvert.
 *
 * @param id identifiant du projet à marquer.
 * @return `AppResult<Unit>` : échec `Storage.NotFound` si le projet est inconnu.
 */
class MarkProjectOpenedUseCase @Inject constructor(
    private val projetRepository: ProjectRepository,
    private val dispatchers: DispatcherProvider,
) {
    operator suspend fun invoke(id: ProjectId): AppResult<Unit> =
        withContext(dispatchers.io) {
            projetRepository.marquerOuvert(id)
        }
}
```

Règles du patron :

- **`operator fun invoke`** : le use case s'appelle comme une fonction
  (`useCase(id)`), sans méthode nommée arbitraire.
- **Injection par constructeur** : repositories, `DispatcherProvider`, horloge
  éventuelle — jamais d'objet statique.
- **`AppResult` en retour** pour toute erreur attendue ; les exceptions ne
  traversent jamais la frontière domaine → UI.
- **`suspend` + `withContext(dispatchers…)`** quand il y a une I/O ; le KDoc
  précise le contexte d'exécution attendu.
- Un use case = **une intention utilisateur** ; si la KDoc décrit deux
  intentions, découper.

## Journalisation

Tout passe par `AppLogger` (API `core:domain`). `android.util.Log`, `println`
et `printStackTrace` sont interdits hors `core:logging` et `core:crash` —
règle detekt `ForbiddenMethodCall` qui fait échouer le build. On journalise
des **identifiants** (id de projet, id de template), jamais un nom de projet,
un chemin, un nom d'auteur ni un contenu de fichier.

## Tests

- JUnit 4 (choix du prompt maître), MockK, Turbine,
  `kotlinx-coroutines-test`, Robolectric, AndroidX Test.
- **Fakes plutôt que mocks** pour les repositories et `FileSystem`
  (`core:testing`).
- Noms de tests **en français avec accents graves** :

  ```kotlin
  @Test
  fun `refuse un nom contenant un slash`() { ... }
  ```

- Couverture ≥ 80 % sur `core:model` et `core:domain` (Kover, seuil vérifié
  par `koverVerify`).
- Chaque bug corrigé reçoit un test de non-régression.
- Aucun test ne dépend de l'horloge réelle ni du réseau : horloge injectée,
  fakes.

## Git

- Commits atomiques, *Conventional Commits* **en français** :
  `feat(newproject): ajoute la validation du nom`,
  `fix(logging): corrige la rotation sous 4 Ko`,
  `chore(release): v0.1.0`.
- Fins de ligne LF (sauf `*.bat`), UTF-8, saut final — normalisés par
  `.gitattributes` et `.editorconfig`.
- `local.properties`, secrets, `dist/` et répertoires de build ne sont jamais
  versionnés.

## Dépendances

- Toute version vit dans `gradle/libs.versions.toml` (et dans le catalogue
  de `build-logic` pour les artefacts de plugins).
- **Vérifier chaque version sur le dépôt officiel** (Google Maven, Maven
  Central, Plugin Portal) avant de l'ajouter — ne jamais la déduire de
  mémoire.
- Chaque dépendance est justifiée ; les dépendances inutilisées sont retirées.

## Livraison (fin d'étape, section 9.2 du prompt maître — vérification graduée, prompt compagnon Vérification-1)

1. `./gradlew spotlessApply` puis la vérification complète — tout au vert —
   en **build incrémental, sans `clean`**. Le `clean` complet est RÉSERVÉ
   aux étapes qui modifient `build-logic`, les convention plugins,
   `gradle/libs.versions.toml`, la déclaration des modules
   (`settings.gradle.kts`), et aux étapes de fin de phase (audit) — le build
   cache restitue alors la recompilation (from cache), la CI GitHub reste
   la garantie from-scratch (ADR 0037). Fiabilité de l'incrémental vérifiée
   empiriquement le 2026-09-24 : `checkModuleDependencies` recense les
   déclarations en phase de configuration — une violation introduite dans
   un build script est attrapée sans `clean` (échec en 6 s, message exact) ;
   detekt n'a pas d'incrément par fichier mais les modules inchangés
   restent up-to-date (mesures T6/G1).
2. Mettre à jour `CHANGELOG.md`, `docs/ROADMAP.md`, `AGENTS.md`, les docs
   concernées et la KDoc.
3. `scripts/bump-version.sh minor` (ou `patch`), commit `chore(release):
   vX.Y.Z`, tag Git **annoté** : `git tag -a vX.Y.Z -m "vX.Y.Z"` (jamais
   un tag léger — `git push origin main --follow-tags` ignore
   silencieusement un tag léger, constaté sur v0.15.0 à v0.23.0).
4. `scripts/package.sh <N>` : archive complète versionnée, APK debug,
   SHA256SUMS dans `dist/`.
5. `scripts/verify-archive.sh` : l'archive est saine et **autonome** (build
   depuis une copie extraite — `GRADLE_USER_HOME` isolé, stable d'une
   exécution à l'autre, daemon actif : prompt Vérification-1, section 2.2).
6. Remettre le rapport (section 14) — désormais avec la **durée réelle** de
   chaque commande de vérification (`spotlessCheck`, `detekt`,
   `checkModuleDependencies`, `lintDebug`, `testDebugUnitTest`,
   `koverVerify`, `assembleDebug`, `verify-archive.sh`,
   `verify-templates.sh` si lancé) — puis attendre la validation de
   l'utilisateur.

`scripts/verify-templates.sh` ne tourne que pour les étapes touchant
réellement aux modèles embarqués, au moteur de modèles, ou aux fichiers
partagés de `build-logic`/catalogue de versions — **jamais par défaut**
(prompt Vérification-1, section 2.3 ; usage historique : étapes 9 et 18).
