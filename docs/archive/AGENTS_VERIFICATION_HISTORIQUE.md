# Historique des directives de vérification (archivé 2026-10-19)

Ce fichier préserve TEL QUEL le texte des directives de vérification
remplacées par `docs/STRATEGIE_VERIFICATION.md` (qui prime). Elles ont
été retirées d'AGENTS.md le 2026-10-19 mais restent la trace des
retours CI qui les ont fondées : v0.31.1, v0.79.0, v0.120.1, v0.121.1,
v0.134.27, v0.134.31, v0.134.40.

## Extrait « Commandes » d'AGENTS.md (avant remplacement)

## Commandes

```bash
source scripts/env.sh                      # JAVA_HOME, ANDROID_HOME, PATH

# VÉRIFICATION STANDARD (directive utilisateur du 2026-09-25, v0.31.1) :
# légère + assembleDebug, à chaque étape / correctif —
./gradlew spotlessCheck detekt            # hygiène (format + statique)
./gradlew :<module>:compileDebugKotlin …  # compilation des modules TOUCHÉS
./gradlew :<module>:testDebugUnitTest …   # tests des modules TOUCHÉS
                                           # (--max-workers=1 sur 4 Go)
./gradlew :app:assembleDebug              # TOUJOURS — l'APK est le produit
# koverVerify, lintDebug, checkModuleDependencies, tests des modules
# non touchés : la CI GitHub les exécute à chaque push (garantie
# from-scratch, ADR 0037) — la chaîne complète reste LA référence des
# audits de fin de phase :
#   ./gradlew spotlessCheck detekt checkModuleDependencies lintDebug \
#     testDebugUnitTest koverVerify assembleDebug   (SANS clean, ADR 0037)
#
# DIRECTIVE OBLIGATOIRE (v0.79.0, retour CI) : pour chaque commit, lancer
# la chaîne COMPLÈTE de vérification sur TOUS les modules touchés —
# pas seulement compileDebugKotlin. Les tests unitaires, lint, kover et
# Hilt doivent passer localement avant le push. La CI exécute la chaîne
# complète (spotlessCheck detekt checkModuleDependencies lintDebug
# testDebugUnitTest koverVerify assembleDebug) et tout échec bloque.
# En pratique : identifier les modules touchés (git diff --name-only),
# puis pour chacun lancer spotlessCheck detekt testDebugUnitTest
# lintDebug koverVerify (si applicable), puis :app:assembleDebug.
# Les changements de catalogues/constantes partagés (ToolchainCatalog,
# ToolManifest, etc.) peuvent casser des tests dans des modules non
# directement modifiés — toujours lancer les tests des modules qui
# référencent les constantes changées.
#
# DIRECTIVE 2026-10-13 (v0.120.1, retour CI) : `checkModuleDependencies`
# fait partie de la vérification LOCALE AVANT PUSH — il échoue là où
# lintDebug/assembleDebug restent verts (listes d'autorisation du plugin
# codeide.module-rules, ex. :lsp:host → :lsp:xml v0.120.1). À l'inverse,
# assembleDebug n'est PLUS un prérequis BLOQUANT du push (la CI l'exécute
# à chaque push, ADR 0037) — chaîne locale de push : spotlessCheck detekt
# checkModuleDependencies + tests/kover/lintDebug des modules touchés.
#
# DIRECTIVE 2026-10-13 (v0.121.1, retour CI) : tout commit qui AJOUTE ou
# MODIFIE des fichiers .kt doit régénérer la baseline de parité du corpus
# AVANT le push — ./gradlew :lsp:kotlin:test -Dparite.majBase=true — le
# diff de baseline fait partie de la revue (VENDOR.md) ; un push sans
# régénération fait échouer la CI sur PariteCorpusDepotTest (constat
# v0.121.0 : XE5 a ajouté 50 fichiers, digeste divergé, build rouge).
#
# BASCULE 2026-10-17 (séparation des dépôts, docs/archive/PLAN_SEPARATION_DEPOTS.md
# §9) : la suite de parité vivait dans :lsp:kotlin — le module a rejoint le
# dépôt séparé jjoblab/codeide-lsp. DANS CE DÉPÔT, il n'y a plus AUCUN test
# de parité : la règle « parité EN DERNIER » s'applique désormais dans
# codeide-lsp (y lancer ./gradlew :lsp:kotlin:test -Dparite.majBase=true).
# Les edits .kt de CodeIDE n'exigent plus de régénération locale.
./gradlew spotlessApply                    # formatage avant commit
scripts/bump-version.sh minor|patch        # incrémente la version
                                            # (patch = correction après retour utilisateur)
git tag -a vX.Y.Z -m "vX.Y.Z"              # tag ANNOTÉ, jamais léger (Vérification-1 §2.5)
scripts/package.sh 0                       # dist/ : archive + APK + SHA256SUMS
scripts/verify-archive.sh dist/CodeIDE-v0.1.0-etape00.zip   # archive autonome ?
                                            # (GRADLE_USER_HOME isolé + daemon actif, Vérification-1 §2.2)
```

## §1 « Push dès que la vérification locale hors-assembleDebug est verte »

### 1. Push dès que la vérification locale hors-`assembleDebug` est verte

- Une étape est **poussable** dès que les vérifications hors `assembleDebug`
  sont vertes localement : `spotlessCheck detekt checkModuleDependencies` +
  `lintDebug testDebugUnitTest koverVerify` sur les modules touchés.
- `assembleDebug` **n'est plus un prérequis bloquant du push** : la CI
  GitHub l'exécute à chaque push (chaîne complète ADR 0037). Si
  l'environnement de l'agent ne permet pas de l'exécuter (pas de SDK
  Android), on s'appuie sur la CI comme seule source de vérité pour cette
  étape, sans attendre.
- Identité Git inchangée : `jjoblab <olson12jb@gmail.com>` (règle
  permanente, section « Identité Git »).


## §7 « Chaîne de push en SÉRIE de chantiers »

### 7. Chaîne de push en SÉRIE de chantiers (directive du 17/10/2026, v0.134.33 — retour v0.134.27/v0.134.31)

Cette directive consolide la séquence de push d'un chantier et la boucle
de travail en série. Elle PRIME sur « les vérifications se font module
par module » (§0) lorsque la chaîne prépare un push.

- **La chaîne locale de push se lance à la RACINE du dépôt (tous
  modules), JAMAIS module par module** :
  `./gradlew spotlessCheck detekt checkModuleDependencies lintDebug testDebugUnitTest koverVerify --stacktrace`
  — exactement la chaîne CI MOINS `assembleDebug` (la CI l'exécute).
  Leçon v0.134.27 : c'est le detekt/test RACINE qui attrape ce que la
  chaîne par-module laisse passer (ex. `SettingsDataStore.toAppSettings`
  62 lignes, détecté par le detekt racine seulement) — un push sur la
  foi d'une chaîne par-module vert N'EST PAS vert.
- **La baseline de parité se régénère EN DERNIER, après tout edit
  `.kt`, et ferme la revue du diff** :
  `./gradlew :lsp:kotlin:test -Dparite.majBase=true`. Leçon
  v0.134.31 : une baseline régénérée AVANT une correction `.kt`
  postérieure (lint, formatage) est PÉRIMÉE — CI rouge sur
  `PariteCorpusDepotTest`. Le diff de baseline attendu est UNE ligne
  (le compteur de fichiers) ; tout autre diff se lit avant push.
- **Séquence d'un chantier** (répétable) :
  1. chaîne locale RACINE verte (hors `assembleDebug`) ;
  2. parité EN DERNIER + revue du diff (une ligne) ;
  3. version incrémentée (`scripts/bump-version.sh patch`) +
     `CHANGELOG.md` à jour ;
  4. commit (`feat(...)`/`fix(...)`, identité jjoblab) ;
  5. push sur `origin/main` ;
  6. **CI surveillée** (§2, toutes les ~5 min) ;
  7. **le chantier suivant démarre IMMÉDIATEMENT en parallèle** —
     l'agent ne reste JAMAIS inactif devant une CI en vol.
- **CI rouge en série** : le correctif part DANS le chantier en cours
  (ou en commit `fix(ci)` isolé si le chantier en cours est indépendant),
  en cascade — l'erreur est lue via l'API (§3) puis corrigée SANS
  abandonner le travail déjà commencé sur le chantier suivant. Le
  chantier suivant re-base si nécessaire (`git pull --rebase`) avant
  son propre push.


## §7.1 « Vérification incrémentale par type de changement »

### 7.1 Vérification incrémentale par type de changement (directive du 17/10/2026, v0.134.40)

La chaîne RACINE de la §7 reste LA référence pour tout ce qui dépasse
un module. Pour un chantier MONO-module, la reprendre en entier est un
gaspillage : la CI exécute la chaîne complète à chaque push et sert de
filet — la chaîne locale n'existe que pour éviter un aller-retour CI
évident. Principe : **la vérification locale couvre ce que le
changement peut casser, rien de plus.**

Règle de choix — « mono-module » = aucun fichier HORS du module touché
ne dépend de ce qui change (signature, port, API exportée). En cas de
doute → §7 (chaîne racine).

| Changement | Vérification locale minimale |
| --- | --- |
| Fix ou feature MONO-module, API du module inchangée | `:mod:spotlessCheck :mod:detekt :mod:lintDebug :mod:testDebugUnitTest` (koverVerify si couverture touchée) + `checkModuleDependencies` RACINE (graphe de dépendances) |
| Signature ou port public d'un module consommé ailleurs (ex. :core:*) | §7 — chaîne RACINE complète (les consommateurs doivent compiler, tester, passer detekt — leçon v0.134.27) |
| Ressources seules (xml/layout/drawable, pas de .kt) | `:mod:lintDebug` + tests UI du module si concernés |
| `build.gradle.kts`, catalogue de versions, dépendances, plugins, CI | §7 — chaîne RACINE complète |
| Release, multi-modules, refactor transversal | §7 — chaîne RACINE complète |
| TOUT edit `.kt` (dans tous les cas) | parité EN DERNIER + revue du diff (une ligne) — DEPUIS la bascule 2026-10-17, ce rituel vit dans le dépôt codeide-lsp (`:lsp:kotlin`) : CodeIDE n'a plus de baseline de parité à régénérer |

Précédent fondateur : v0.134.40 (hotfix NPE de l'aperçu, rapport
9774dd61) — chaîne ciblée :feature:preview + `checkModuleDependencies`
racine + parité EN DERNIER : push du premier coup, CI verte (~10 min).
La leçon v0.134.27 (detekt racine a attrapé :core:datastore) reste
valable et fonde la ligne « signature/port partagé » : c'est
exactement le cas où une chaîne par-module N'EST PAS suffisante.

