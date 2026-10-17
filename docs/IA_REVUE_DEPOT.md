# Revue du dépôt avant les fonctionnalités IA

> Revue du dépôt `jjoblab/CodeIDE` (v0.134.41, commit `1e8ca78`) et de la bibliothèque
> `jjoblab/code-editor`, limitée à ce qui conditionne l'ajout de l'IA. Chaque constat a été
> **vérifié par lecture du code** ; ce qui n'a pas pu l'être est signalé « à vérifier ».
> Date : 2026-10-17.

## Résumé

L'architecture est saine et bien outillée (modules à règles vérifiées, KDoc, `explicitApi`,
historique local, tiroir à fragments, section Paramètres → IA déjà prévue). L'ajout de l'IA
est faisable **sans refonte**, à condition de traiter d'abord quatre points de sécurité et de
process (lot IA-0) : permission réseau à justifier par ADR, sauvegarde automatique non
maîtrisée, expurgation des journaux sans motif de clé, absence de coffre de secrets.

## Points bloquants (à traiter dans le lot IA-0, avant tout code IA)

| # | Fichier | Constat | Gravité |
|---|---|---|---|
| 1 | `docs/adr/0034-…md`, `AGENTS.md` (« Aucune permission INTERNET… Phase 1 »), `README.md` | L'ADR 0034 limite `INTERNET` à l'installateur et exige une ADR pour tout autre usage. Envoyer du code à un fournisseur d'IA est un nouvel usage. | 🔴 Process |
| 2 | `app/src/main/AndroidManifest.xml` (`<application>`) | Ni `allowBackup`, ni `dataExtractionRules`, ni `fullBackupContent` : la sauvegarde automatique est active par défaut. Des réglages ou blobs de clés IA seraient inclus. | 🔴 Sécurité |
| 3 | `core/domain/.../LogRedactor.kt` | Motifs présents : courriels, `content://`, `file://`, chemins absolus, hachages. **Aucun motif de clé/jeton** (`sk-…`, `Bearer`, `x-api-key`). Une clé dans un message d'erreur fuirait vers les journaux exportables et rapports de plantage (règle 11). | 🔴 Sécurité |
| 4 | dépôt entier | Aucun usage d'`AndroidKeyStore` : pas de coffre de secrets existant. | 🔴 Manque |

## Importants

| # | Fichier | Constat | Catégorie |
|---|---|---|---|
| 5 | manifeste / `res/xml` | Aucun `network_security_config.xml`. Avec `targetSdk 28` le trafic en clair est déjà interdit par défaut, mais ce n'est écrit nulle part et aucune exception de boucle locale n'existe pour un modèle servi sur l'appareil. | Sécurité |
| 6 | `core/model/.../AppSettings.kt` | ~40 champs, `@Suppress("LongParameterList")` ; le detekt racine a déjà sanctionné son mapper (leçon v0.134.27). Y ajouter les réglages IA est à proscrire → store dédié (ADR 0122). | Maintenabilité |
| 7 | `feature/editor/.../EditorActivity.kt` (2 762 l.), `EditorViewModel.kt` (5 009 l.) | Classes déjà très volumineuses. La logique IA doit vivre ailleurs ; seuls des crochets minces (≲ 80 lignes) sont acceptables. | Maintenabilité |
| 8 | `code-editor` : `cel-lsp-api/.../ViewZone*.java` | `ViewZone`/`ViewZoneProvider` existent (SPI de `Language`) mais **aucun code de `cel-ui` ne les consomme** : le diff « entre deux lignes » n'est pas disponible. Le plan utilise une carte de diff (ADR 0126) et reporte ce chantier. | Dépendance |
| 9 | `core/database` | Room en `version = 1`, `exportSchema = true` : l'historique des conversations impose une migration 1→2 **testée** (schéma exporté à committer). | Correctitude |
| 10 | `activity_editor.xml` et autres | Des commentaires du dépôt nomment encore un projet tiers du même type (règle 13, décision du 2026-10-15). Sans lien avec l'IA, mais à purger via le prompt dédié ; **les nouveaux fichiers IA ne doivent citer aucun projet tiers**. | Conformité |
| 11 | `README.md` | Indique « étape 11 terminée … v0.12.0 » alors que `version.properties` est à 0.134.41 : documentation périmée, à rafraîchir au lot IA-0 (permission réseau, modules). | Documentation |

## Suggestions

| # | Sujet | Suggestion | Catégorie |
|---|---|---|---|
| 12 | Réseau | Un seul `AiHttpTransport` ; timeouts distincts connexion / lecture de flux (le flux IA dépasse les constantes du bootstrap) ; annulation = fermeture de socket. | Performance |
| 13 | Tests réseau | Serveur `com.sun.net.httpserver` du JDK + fichiers dorés de flux SSE : aucune dépendance de test supplémentaire. | Tests |
| 14 | Version des dépendances | Aucune dépendance nouvelle prévue. Si OkHttp est un jour retenu : vérifier la version sur Maven Central (règle 9). | Process |
| 15 | Agent | S'appuyer sur `EtiqueteurHistorique` (point d'extension « actions risquées ») et `HistoriqueLocal.listerRevisions/lireContenu` pour la restauration ; vérifier avant d'écrire un cas d'usage de restauration qu'il n'en existe pas déjà un dans `FeuilleHistoriqueFragment`/`HistoriqueViewModel` (**à vérifier**). | Réutilisation |
| 16 | Chemins projet | Réutiliser `ResoudreRepertoireProjet` (pont SAF → FUSE) ; ne jamais passer de chemin absolu au modèle. | Sécurité |
| 17 | Vérification CI | La baseline de parité du corpus vit désormais dans `codeide-lsp` : aucune régénération locale pour les `.kt` de ce dépôt (AGENTS.md, bascule 2026-10-17). | Process |

## Ce qui est bien (à conserver)

- `checkModuleDependencies` + `ModuleRulesPlugin` : garde-fou automatique, extensible.
- Patron `FabriqueFragmentTerminalTiroir` : modèle direct pour entrer dans le tiroir.
- Historique local avec contenu précédent, pierres tombales et étiquettes : filet de
  sécurité pour un agent qui écrit.
- Section `SectionParametres.IA` + `BientotFragment` : point d'entrée déjà câblé.
- Bibliothèque d'édition : `addSelectionAction`, `EditorDecorationPainter`, point de
  mutation unique `replaceRange`, annulation par lot.
- Conventions strictes (pas de `!!`, dispatchers injectés, `AppResult`/`AppError`,
  `CancellationException` relancée) : directement applicables au code IA.

## Verdict

**Needs Discussion → Approve sous conditions.** Aucun obstacle structurel. Accepter les
ADR 0121 à 0126 et exécuter le lot IA-0 (points 1 à 5) avant tout lot fonctionnel.
