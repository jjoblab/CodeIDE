# Mission « IA » — plan d'exécution pour l'agent

> Document **opérationnel** : un agent IA qui le lit en entier, plus `AGENTS.md`, les ADR
> 0121 à 0126, `docs/IA_REVUE_DEPOT.md`, `docs/IA_SPEC_ECRANS.md` et la maquette
> `docs/mockups/ia/ia-ecrans.html`, sait exactement quoi construire, dans quel ordre,
> avec quels critères de réussite. Base : CodeIDE v0.134.41. Date : 2026-10-17.

## 1. Objectif et périmètre

**Objectif** : ajouter à CodeIDE un assistant IA « apporte ta clé » (ADR 0121) :
chat contextuel, édition assistée dans l'éditeur, explication/correction des erreurs,
et un agent capable de proposer des modifications multi-fichiers **sous contrôle**
(ADR 0125).

**Inclus** : fournisseurs Anthropic (Messages) et compatibles OpenAI ; coffre de clés ;
réglages et consentement ; chat en streaming dans le tiroir ; contexte (fichier, sélection,
diagnostics, `@fichiers`) ; actions dans l'éditeur ; historique des conversations ; agent
avec approbations et restauration.

**Exclus (hors chantier)** : backend CodeIDE, modèle embarqué, terminal/`run_shell` pour
l'agent, recherche sémantique (onglet « Recherche » réservé), diff en ligne (IA-5b optionnel),
texte fantôme (IA-8 optionnel), synchronisation entre appareils.

## 2. Règles impératives pour l'agent

Ces règles reprennent `AGENTS.md` ; en cas de doute, `AGENTS.md` prime.

1. **Kotlin 100 %**, identifiants en anglais ; **KDoc, commentaires, commits, docs, messages d'erreur en français**. Noms de ressources (layouts, ids, chaînes) en français `snake_case` comme l'existant (`fragment_*`, `ligne_*`, `feuille_*`, `popover_*`).
2. **Vues XML + ViewBinding**, Activities/Fragments, Material 3. **Pas de Compose.**
3. **Aucune ressource en dur** : couleurs via tokens/attributs de thème (`core:ui`), dimensions via `dimens.xml`, textes dans `strings.xml` **FR (`values/`) et EN (`values-en/`)**.
4. Interdits : `!!`, `GlobalScope`, `runBlocking` (hors tests), `Thread.sleep`, `catch` qui avale l'erreur, `@Suppress` sans justification commentée. Dispatchers **injectés** (`DispatcherProvider`). Erreurs attendues = `AppResult`/`AppError`. `CancellationException` toujours relancée.
5. **Aucune logique métier** dans Activity/Fragment/ViewModel : cas d'usage dans `:core:ai`.
6. **Aucun secret** dans le dépôt, les journaux, les états d'UI, les rapports (ADR 0124). Pas de `.gh-token` versionné.
7. **Dépendances** : aucune nouvelle prévue. Toute addition passe par `gradle/libs.versions.toml`, justifiée, avec **version vérifiée** sur Maven Central/Google Maven (**jamais devinée**).
8. **Aucune mention de projet tiers du même type que CodeIDE** (autre IDE/éditeur Android, son dépôt, ses classes, son auteur) dans le code, les KDoc, les docs, le CHANGELOG, les commits, les tags (règle 13). L'étude comparative éventuelle reste interne à la session.
9. **Identité Git** exclusive : `jjoblab <olson12jb@gmail.com>` ; commits conventionnels en français (`feat(ia): …`) ; tags **annotés** poussés explicitement.
10. **Pas de nom de modèle d'IA codé en dur** (ADR 0123 §6) ; pas de clé, URL privée ou jeton dans les tests (utiliser des valeurs factices évidentes).
11. Aucun `TODO` sans ticket tracé (`docs/ROADMAP.md`). Aucune vérification désactivée pour « faire passer » le build.
12. Avant d'écrire un cas d'usage ou un composant, **chercher s'il existe déjà** (historique, diff, recherche, thème, fabriques) et le réutiliser.

## 3. Vue d'ensemble des lots

| Lot | Version | Titre | Dépend de | Livrable principal |
|---|---|---|---|---|
| **IA-0** | 0.135.0 | Préalables et garde-fous | ADR acceptées | Réseau explicite, sauvegarde, `LogRedactor`, modules vides + règles, docs |
| **IA-1** | 0.136.0 | Domaine IA et fournisseurs | IA-0 | `:core:ai` + `:ai:providers`, streaming testé |
| **IA-2** | 0.137.0 | Coffre, réglages, consentement | IA-1 | `KeyVault`, `AiSettingsStore`, Paramètres → IA, test de connexion |
| **IA-3** | 0.138.0 | Chat dans le tiroir | IA-2 | Destination « IA », Chat en streaming, Markdown |
| **IA-4** | 0.139.0 | Contexte et confidentialité | IA-3 | `ContextAssembler`, `@fichiers`, exclusions, détection de secrets |
| **IA-5** | 0.140.0 | Intégration éditeur | IA-4 | « Demander à l'IA », carte de diff, actions Problèmes/Console |
| **IA-6** | 0.141.0 | Historique des conversations | IA-3 | Room v2 + migration testée, écran Historique |
| **IA-7** | 0.142.0 | Agent, outils, approbations | IA-4, IA-5 | `AgentRunner`, `ChangeSet`, restauration |
| IA-5b | optionnel | Diff en ligne (`ViewZone`) | IA-5 + lib | Chantier dans `code-editor` |
| IA-8 | optionnel | Complétion « texte fantôme » | IA-4 | ADR propre |
| **IA-9** | 0.143.0 | Durcissement et release | tous | Tests d'architecture, accessibilité, perf, docs |

Chaque lot = une version `0.N.0` validée par l'utilisateur (« GO lot suivant »). Les
correctifs sont des `0.N.M`.

## 4. Détail des lots

### IA-0 — Préalables et garde-fous (0.135.0)
**Objectif** : rendre le dépôt sûr **avant** d'y faire transiter la moindre donnée IA.

**Tâches**
1. Passer les ADR 0121 à 0126 en « accepté » (après validation du propriétaire).
2. `app/src/main/res/xml/network_security_config.xml` : `base-config cleartextTrafficPermitted="false"` ; `domain-config` cleartext **uniquement** `localhost` et `127.0.0.1` ; référence `android:networkSecurityConfig` dans `<application>`.
3. `res/xml/data_extraction_rules.xml` + `res/xml/backup_rules.xml` excluant le répertoire `no_backup` et le futur fichier DataStore des réglages IA ; références manifeste (`android:dataExtractionRules`, `android:fullBackupContent`).
4. Étendre `LogRedactor` (motifs ADR 0124 §3) **+ tests** (un cas par motif, faux positifs : hachages `/h-…`, identifiants Gradle).
5. Créer les 4 modules (`:core:ai`, `:ai:providers`, `:core:ai-storage`, `:feature:ai`) **vides compilables**, avec `README.md` + `Module.md`, `explicitApi()` pour les modules JVM purs, entrées dans `settings.gradle.kts` et `ModuleRulesPlugin` (règles de l'ADR 0122), seuil Kover ≥ 80 % pour `:core:ai` et `:ai:providers`.
6. `core:ui` : interfaces `FabriqueFragmentIaTiroir`, `ControleurIaTiroir`, `PontIaEditeur` (KDoc complet). `core:domain` : `PortEditeurActif` + registre (sans implémentation encore).
7. Docs : `AGENTS.md` (permission INTERNET : deux usages ; section « Mission IA » renvoyant à ce plan), `README.md` (version et modules rafraîchis), `docs/ARCHITECTURE.md` (modules + règles), `CHANGELOG.md`, `docs/ROADMAP.md` (nouvelle phase IA).

**Critères d'acceptation**
- `checkModuleDependencies` vert **et** échoue si l'on ajoute volontairement `:feature:editor → :feature:ai` (test manuel consigné dans le rapport).
- Test unitaire : une chaîne contenant `sk-` + 30 caractères, un en-tête `Authorization: Bearer …` et `x-api-key: …` ressortent masqués.
- L'APK debug démarre ; le bootstrap fonctionne toujours (aucune régression réseau : le téléchargement de l'ADR 0034 reste opérationnel — vérifier la config réseau sur appareil).
- `aapt dump`/manifest fusionné montre les 3 attributs ajoutés.

**Vérification** : chaîne **racine** complète hors `assembleDebug` (changement de `build.gradle.kts`/manifeste/catalogue → AGENTS §7.1).

### IA-1 — Domaine IA et fournisseurs (0.136.0)
**Objectif** : parler à un modèle, en flux, sans aucune UI.

**Livrables**
- `:core:ai` : `AiMessage` (rôles, blocs texte/outil), `AiRequest`, `AiEvent`, `AiModelInfo`, `ToolSpec`, port `AiProvider` (`stream`, `listModels`, `ping`), `SendMessageUseCase` (renvoie un `Flow<AiEvent>`), `TokenBudget` (estimation grossière + plafond).
- `:ai:providers` : `AiHttpTransport`, `SseParser`, `AnthropicProvider`, `OpenAiCompatibleProvider`, mappage d'erreurs HTTP → `AppError`, backoff borné (429/5xx avant 1ᵉʳ jeton, `Retry-After` respecté).

**Tests (JVM pur, serveur `com.sun.net.httpserver`)** : flux nominal ; flux tronqué ; ligne `data:` multiple ; `event:` inconnu ignoré ; UTF-8 coupé en milieu de séquence ; 401, 403, 429 avec `Retry-After`, 500 ; délai dépassé ; **annulation du `Flow` ⇒ socket fermée < 1 s** ; appel d'outil fragmenté en plusieurs deltas ; fichiers dorés dans `src/test/resources/flux/`.

**Critères d'acceptation** : couverture Kover ≥ 80 % sur les deux modules ; aucun log ne contient en-têtes d'authentification ni corps ; HTTP en clair refusé hors boucle locale (test) ; aucun nom de modèle codé en dur.

**Vérification** : §7 racine (nouveaux modules + catalogue éventuel). Aucune parité à régénérer ici.

### IA-2 — Coffre, réglages, consentement (0.137.0)
**Livrables**
- `:core:ai-storage` : `KeyVault` (ADR 0124 §1), `AiSettingsStore` (DataStore dédié : fournisseur actif, URL de base, modèle, plafonds, interrupteurs de confidentialité, consentement donné/date).
- `:feature:ai` : écrans **Paramètres → IA** (liste des fournisseurs, détail d'un fournisseur, confidentialité, limites), **consentement** de premier usage, test de connexion (`ping`) et « Actualiser les modèles » (`listModels`). Remplacer le cas `IA` de `BientotFragment` dans `feature:settings` par l'écran réel via `SectionParametres.IA` (navigation existante, `AppNavigator`).
- Strings FR/EN, layouts de `docs/IA_SPEC_ECRANS.md` §4 à §6.

**Critères d'acceptation**
- La clé saisie n'apparaît **dans aucun** état d'UI, journal, `Bundle`, capture de rotation ; après rotation, le champ est vide et l'aperçu `••••1234` demeure.
- Perte simulée de la clé Keystore ⇒ état « Clé à ressaisir », aucun plantage.
- Sans consentement, **aucune** requête réseau IA ne part (test).
- Procédure manuelle appareil réel consignée dans `docs/TESTS_MANUELS.md` (saisie clé, redémarrage, restauration de sauvegarde ⇒ clé absente).

**Vérification** : §7 racine (nouveau port consommé + manifeste éventuel).

### IA-3 — Chat dans le tiroir (0.138.0)
**Livrables**
- `FabriqueFragmentIaTiroir` fournie par `:feature:ai` (`di/TiroirIaBindsModule`), `IaTiroirFragment` avec sous-onglets **Chat · Agent · Historique** (Agent et Historique en état « bientôt » générique tant que IA-6/IA-7 ne sont pas livrés) et **Recherche** réservé.
- `feature:editor` : 6ᵉ destination au rail (`construireRail()`, `selectionnerDestination()`, `TAG_IA`), **câblage ≲ 40 lignes**.
- `ChatFragment`/`ChatViewModel` (UiState/Action/Effect), `AdapterMessages`, rendu Markdown minimal maison (titres, gras/italique, code en ligne, blocs de code avec langue, listes, liens) **dans `:feature:ai`** avec tests ; actions de bloc de code : **Copier**, **Insérer au curseur** (via `PortEditeurActif`).
- Streaming progressif, bouton **Arrêter**, **Régénérer**, copie d'un message, états : vide, saisie, génération, erreur (clé, réseau, quota), hors-ligne.

**Critères d'acceptation**
- Tiroir à 45 % et à 98 %, écran 360 dp : le rail à 6 éléments reste lisible (libellé « IA » 10 sp, zones tactiles ≥ 48 dp).
- Rotation, clavier ouvert/fermé, panneau inférieur étendu : aucune régression d'insets (réutiliser `GestionnaireInsetsEditeur`).
- Annuler la génération n'arrête pas seulement l'affichage : la requête est **fermée**.
- TalkBack : chaque message annoncé avec son rôle ; bouton Envoyer/Arrêter avec `contentDescription`.

**Vérification** : §7 racine (touche `feature:editor` + `core:ui`).

### IA-4 — Contexte et confidentialité (0.139.0)
**Livrables** : `ContextAssembler` (fichier actif, sélection, diagnostics, `@fichiers`, liste des chemins), puces de contexte retirables dans le compositeur, sélecteur `@` (recherche de fichiers du projet), budget de jetons visible, liste d'exclusion par défaut + `.codeide/ia-ignore`, balayage de secrets avant envoi avec boîte de dialogue bloquante, réécriture des chemins en relatifs, encadrement neutre du contenu non fiable.

**Critères d'acceptation** : un fichier `.env` ou `*.jks` mentionné est **refusé avec message** ; un contexte contenant une clé factice déclenche l'avertissement ; aucun chemin absolu de l'appareil ne figure dans la requête (test sur le corps envoyé au faux serveur) ; la puce de contexte reflète **exactement** ce qui part.

### IA-5 — Intégration éditeur (0.140.0)
**Livrables** : `SelectionAction` « Demander à l'IA » (enregistrée via `EditorView.addSelectionAction`), barre de consigne en ligne avec actions rapides, **carte de diff** (`DiffUnifie` + `ligne_diff.xml`) avec **Accepter / Rejeter / Réessayer**, application en **un lot** (`beginBatch/endBatch`) ; actions « Corriger avec l'IA » (Problèmes) et « Expliquer » (Console/Logcat) ouvrant le Chat pré-rempli.

**Critères d'acceptation** : un seul Annuler défait une édition acceptée ; si le texte a changé depuis la génération (révision différente), « Accepter » est refusé avec proposition de relancer ; sélection vide ⇒ action grisée ; carte et barre au-dessus du clavier et de la barre de symboles sans masquer l'éditeur.

**Optionnel IA-5b** : diff en ligne via `ViewZone` — tâches dans `jjoblab/code-editor` (rendu des zones dans `cel-ui`, tests, tag publié), puis montée de version dans `gradle/libs.versions.toml` (version **vérifiée**).

### IA-6 — Historique des conversations (0.141.0)
**Livrables** : Room v2 (`ai_conversation`, `ai_message`), migration 1→2 **avec test de migration** et schéma exporté committé, `ConversationStore` (port `core:domain`, implémentation `core:database`/`core:ai-storage`), écran Historique (liste, recherche, renommer, supprimer, tout effacer, rétention 7 j / 30 j / illimitée).

**Critères d'acceptation** : migration testée sur base v1 non vide ; suppression d'une conversation = suppression de ses messages ; contenu jamais journalisé ; purge de rétention à l'ouverture.

### IA-7 — Agent, outils, approbations (0.142.0)
**Livrables** : `AiTool`, `ToolRegistry`, outils V1 de l'ADR 0125, `ProjectPathResolver`, `ChangeSet`, `ApprovalGate`, `AgentRunner` borné, service premier plan + notification (annulable), écran **Agent** (plan, étapes, appels d'outils, cartes d'approbation, revue des changements), application via `FileSystem` avec étiquette d'historique, **« Annuler cette exécution »**.

**Critères d'acceptation**
- Tests d'**évasion de chemin** : `../`, lien symbolique sortant, chemin absolu, fichier exclu ⇒ refus.
- Test d'**injection** : un fichier contenant des consignes ne déclenche ni écriture ni `run_gradle` sans approbation.
- Fichier modifié entre proposition et application ⇒ **conflit**, jamais d'écrasement silencieux.
- Historique local désactivé/en échec ⇒ application refusée.
- Limites (étapes, jetons, délai) atteintes ⇒ état « interrompu » lisible, aucune boucle infinie.
- Annuler l'exécution restaure exactement l'état précédent (création, modification, suppression).

### IA-8 (optionnel) — Complétion « texte fantôme »
ADR propre d'abord (déclenchement après pause, annulation à la frappe, plafond de coût, mode FIM selon le fournisseur). Implémentation via `EditorDecorationPainter` + Tab. Désactivée par défaut.

### IA-9 — Durcissement et release (0.143.0)
Tests d'architecture (aucun log d'en-tête `Authorization` ; aucun nom de modèle codé en dur ; aucune requête réseau IA hors `AiHttpTransport` ; aucune requête sans action utilisateur), accessibilité (TalkBack, contraste en thème sombre/clair/dynamique, tailles de police système ×1,3), performances (liste de messages ≥ 500 éléments fluide, mémoire stable pendant un long flux), paysage et grand écran, documentation utilisateur, `CHANGELOG`, `ROADMAP`, revue finale des ADR (statut « accepté »).

## 5. Boucle de travail par lot (AGENTS §7 et §7.1)

1. Lire : ce plan, l'ADR concerné, la spec d'écrans, le code existant touché.
2. Implémenter **uniquement** le périmètre du lot (pas de débordement).
3. Vérification locale :
   - lot touchant plusieurs modules, `build.gradle.kts`, manifeste, catalogue ou un port partagé (IA-0, 1, 2, 3, 5, 7…) : `./gradlew spotlessCheck detekt checkModuleDependencies lintDebug testDebugUnitTest koverVerify --stacktrace` **à la racine** ;
   - lot strictement mono-module sans API exportée : chaîne ciblée du module + `checkModuleDependencies` racine.
   - Mémoire : 4 Go ⇒ `-Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=384m"` si besoin ; arrêter les daemons entre builds.
4. `scripts/bump-version.sh` (voir le script pour l'option « minor ») + `CHANGELOG.md`, `ROADMAP.md`, `AGENTS.md` à jour.
5. Commit conventionnel français (`feat(ia): …`), identité `jjoblab`, push `origin/main`, tag annoté `vX.Y.Z` poussé explicitement.
6. Surveiller la CI toutes les ~5 min ; démarrer en parallèle la préparation du lot suivant ; corriger toute CI rouge sans abandonner le travail en cours.
7. **Rapport de fin de lot** (format ci-dessous), puis **attendre le « GO »**.

### Gabarit de rapport de fin de lot
```
Lot IA-N — <titre> — v0.N.0
Livré : <liste courte>
Écarts au plan : <aucun | liste justifiée>
Vérifications (commande → durée → résultat) : …
Tests ajoutés : <n> (couverture Kover : <x %>)
Points d'attention appareil réel : …
ADR touchés : <numéros et statut>
Risques restants : …
Prochain lot proposé : IA-N+1
```

## 6. Prompt de démarrage à donner à l'agent

> Tu es l'agent d'ingénierie Android/Kotlin du dépôt `jjoblab/CodeIDE`. Lis intégralement,
> dans l'ordre : `AGENTS.md`, `docs/IA_PLAN_EXECUTION.md`, `docs/IA_REVUE_DEPOT.md`,
> les ADR 0121 à 0126, `docs/IA_SPEC_ECRANS.md` et ouvre la maquette
> `docs/mockups/ia/ia-ecrans.html`. Exécute **un seul lot à la fois**, en commençant par
> **IA-0**, strictement dans son périmètre. Respecte les règles de la section 2 du plan
> (notamment : Kotlin, vues XML sans Compose, ressources FR/EN, aucun secret, aucune
> dépendance non vérifiée, aucune mention de projet tiers du même type, identité Git
> `jjoblab`). Avant d'écrire un composant, cherche s'il existe déjà. À la fin du lot :
> chaîne de vérification adaptée, version, CHANGELOG, commit, push, tag annoté, surveillance
> de la CI, puis rapport au format du plan et **attente du « GO »**. Si un point du plan
> contredit le code réel, **ne devine pas** : signale-le dans le rapport et propose un
> amendement d'ADR.
