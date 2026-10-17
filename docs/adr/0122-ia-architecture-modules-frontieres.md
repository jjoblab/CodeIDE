# ADR 0122 — IA : architecture des modules et frontières

- **Statut** : accepté (propriétaire, 2026-10-18 — « GO lot IA-0 »)
- **Date** : 2026-10-17
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0121 (réseau/BYOK). **Contraint par** : règles 1, 2, 4, 5, 6 d'`AGENTS.md`,
  `ModuleRulesPlugin` (`checkModuleDependencies`), ADR 0002 (vues XML, pas de Compose),
  ADR 0053 (fabrique de fragment Hilt pour le tiroir).
- **Contexte** :
  - `EditorActivity` fait 2 762 lignes et `EditorViewModel` 5 009 : y loger la logique
    IA aggraverait la dette. L'IA doit vivre dans ses propres modules, l'éditeur
    n'exposant que des **crochets minces**.
  - Les features ne dépendent pas d'autres features (règle `ModuleRulesPlugin`) ;
    le précédent `FabriqueFragmentTerminalTiroir` (`core:ui`) montre comment un
    fragment d'une feature entre dans le tiroir de l'éditeur sans dépendance directe.
  - `AppSettings` compte déjà ~40 champs (`@Suppress("LongParameterList")`) et le
    detekt racine a déjà sanctionné son mapper (leçon v0.134.27) : y ajouter les
    réglages IA est exclu.
  - Le catalogue ne contient aucun client réseau tiers ; `core:domain` et `core:model`
    sont Kotlin JVM purs avec `explicitApi()`.

## Décision

### Modules créés

| Module | Nature | Rôle | Peut dépendre de |
|---|---|---|---|
| `:core:ai` | Kotlin JVM pur, `explicitApi()` | Modèle (`AiMessage`, `AiEvent`, `ToolSpec`…), ports (`AiProvider`, `AiTool`, `ApprovalGate`), cas d'usage (`SendMessageUseCase`, `RunAgentUseCase`), assemblage du contexte, budget de jetons, boucle d'agent | `:core:model`, `:core:domain` |
| `:ai:providers` | Kotlin JVM pur | `AiHttpTransport` (HttpURLConnection), parseur SSE, adaptateurs Anthropic (Messages) et « compatible OpenAI » (Chat Completions), reprises/backoff | `:core:ai`, `:core:model`, `:core:domain` |
| `:core:ai-storage` | Bibliothèque Android | `KeyVault` (Android Keystore), `AiSettingsStore` (DataStore dédié), implémentation du stockage des conversations (via `:core:database`) | `:core:ai`, `:core:model`, `:core:domain`, `:core:datastore`, `:core:database` |
| `:feature:ai` | Android (vues XML + ViewBinding) | Fragments Chat / Agent / Historique, écrans de réglages IA, consentement, rendu Markdown | `:core:ui`, `:core:domain`, `:core:model`, **`:core:ai`** (exception feature explicite, comme `:feature:terminal` → `:core:terminal-runtime`) |

### Contrats d'accroche (aucune dépendance feature → feature)

- `core:ui` : `FabriqueFragmentIaTiroir` (même patron que `FabriqueFragmentTerminalTiroir`),
  `ControleurIaTiroir` (commandes que le fragment adresse à son hôte), `PontIaEditeur`
  (ce que l'éditeur appelle : « demander à l'IA sur cette sélection », « corriger ce diagnostic »).
- `core:domain` : `PortEditeurActif` (lire fichier actif / sélection / diagnostics ;
  appliquer un lot d'édition **atomique** — un seul pas d'annulation), registre singleton
  alimenté par `feature:editor` (même esprit que le port `SourceApercu` des previews).
- Liaisons Hilt : `feature:ai` fournit `FabriqueFragmentIaTiroir` et `PontIaEditeur`
  (module `di/TiroirIaBindsModule`, miroir de `TiroirTerminalBindsModule`).

### Règles de dépendance à ajouter à `ModuleRulesPlugin`
`:core:ai` → `{:core:model, :core:domain}` ; `:ai:providers` → `{:core:ai, :core:model, :core:domain}` ;
`:core:ai-storage` → `{:core:ai, :core:model, :core:domain, :core:datastore, :core:database}` ;
`:feature:ai` → trio standard + `:core:ai`. **Interdits** : `:feature:editor` → `:feature:ai`,
`:core:ai` → Android, `:ai:providers` → Android.

### Réglages IA hors de `AppSettings`
Store dédié (`AiSettingsStore`, fichier DataStore propre) : fournisseur actif,
modèle, plafonds, consentement, options de confidentialité. `SectionParametres.IA`
existe déjà (écran « bientôt ») : le lot IA-2 remplace ce cas de `BientotFragment`
par l'écran réel.

## Options considérées

### Option A — Modules dédiés + ports minces dans `core:ui`/`core:domain` (retenue)
| Dimension | Évaluation |
|---|---|
| Complexité | Moyenne (4 modules, règles à déclarer) |
| Coût | Un lot d'échafaudage (IA-0) |
| Scalabilité | Bonne : providers/outils ajoutables sans toucher l'éditeur |
| Familiarité | Élevée (patron déjà utilisé par terminal et previews) |

### Option B — Tout dans `feature:editor`
Complexité apparente nulle, mais ajoute des milliers de lignes à deux classes déjà
massives, rend le module intestable et viole l'esprit d'ADR 0100/0118 (réduire le
couplage de `EditorActivity`). **Écartée.**

### Option C — Un module unique `:feature:ai` contenant domaine, réseau et UI
Plus simple à créer, mais le domaine et le réseau ne seraient plus Kotlin JVM purs
(tests lents Robolectric), et la règle « logique métier hors Fragment/ViewModel »
perd sa frontière vérifiable. **Écartée.**

## Analyse des compromis
A coûte un échafaudage initial mais garde la logique IA testable en JVM pur
(seuil Kover ≥ 80 % applicable à `:core:ai` et `:ai:providers`) et limite l'impact
sur l'éditeur à quelques dizaines de lignes de câblage.

## Conséquences
- **Plus facile** : tests unitaires rapides ; remplacement d'un fournisseur sans
  toucher l'UI ; revue de diff limitée.
- **Plus difficile** : 4 modules à maintenir ; `checkModuleDependencies` doit être
  étendu avant tout code (lot IA-0).
- **À revoir** : si un 2ᵉ consommateur apparaît (ex. aperçu XML), généraliser `PortEditeurActif`.

## Actions
1. [ ] Étendre `ModuleRulesPlugin` (lot IA-0) et exécuter `checkModuleDependencies` racine.
2. [ ] Créer les 4 modules avec `README.md` + `Module.md` (convention du dépôt).
3. [ ] Ajouter `FabriqueFragmentIaTiroir`, `ControleurIaTiroir`, `PontIaEditeur` à `core:ui` ; `PortEditeurActif` à `core:domain`.
4. [ ] Documenter les modules dans `docs/ARCHITECTURE.md` (tableaux « Modules » et « Règles de dépendance »).
