# Reprise du lot L4 — chantier générateur / Nouveau projet

> Contexte de reprise écrit le 2026-11-10, après un push L4 « en cours »
> (validation locale impossible : réseau du sandbox limité par Maven
> Central, HTTP 429). Ce fichier décrit l'état exact du lot L4, ce qui
> reste à faire et les commandes de reprise. Il est supprimé à la fin du
> lot (commit de nettoyage).

## État du chantier

| Lot | État | Commits |
|---|---|---|
| L1 — socle moteur | ✅ poussé | 053c52c4, 49355ecb (v0.209.0) |
| L2 — outil + matrice | ✅ poussé | 57875d42, 9aa65b2d (v0.210.0) |
| L3 — modèles hors Android + mutualisation | ✅ poussé, CI verte | 671b1e6f, 22097a96, 61aba843 (v0.211.0/0.211.1) |
| **L4 — modèles Android** | **⏳ en cours (pushé non validé)** | ce commit |
| L5 — écrans (L5a-L5d) | ⛔ non commencé | — |

## Ce que contient le lot L4 (fait, en attente de validation)

### android-app (templateVersion 2.0.0)

1. **Câblage réel des options 12-16** (plan §7) :
   - `includeCoroutines` → usage réel (`CoroutineScope` + `Dispatchers`) dans
     `MainActivity` (variante vide sans navigation) et `BienvenueFragment` ;
     param masqué (`visibleWhen`) quand `language != "kotlin"`.
   - `includeRetrofit` → permission `INTERNET` (manifeste) +
     `ServiceCitations.kt/.java` (API d'exemple + convertisseur Gson) ;
     désactivé avec raison (`disabledReasonKey`) si `no-activity` ;
     AVERTISSEMENT « pensez à Coroutines » si absent (contrainte
     `hint.retrofit.sans.coroutines`).
   - `includeNavigation` → `graphe_principal.xml` (res/navigation) +
     `NavHostFragment` dans `activity_main.xml` (variante vide) **et**
     `activity_main_tiroir.xml` (intégration tiroir : identifiants du menu
     `tiroir_accueil`/`tiroir_apropos` = destinations du graphe, argument
     `espace` compatible `FragmentAccueil`, câblage `NavigationUI` +
     `AppBarConfiguration`) ; `BienvenueFragment` + `fragment_bienvenue.xml`
     en variante vide ; `MainActivityNavigation.kt/.java` séparés.
   - `includeRoom` → base instanciée et EMPLOYÉE : `BddLocale` (Kotlin :
     entité + DAO + `compter()` inline ; Java : `TacheEntity`/`TacheDao`
     séparés avec `compter()`), usage réel hors thread UI (`Executors`) ;
     INFO « Hilt recommandé » (`hint.room.sans.hilt`).
   - `includeHilt` → `@HiltAndroidApp` (`Application`), `@AndroidEntryPoint`
     (MainActivity, MainActivityNavigation, BienvenueFragment), modules
     `ModuleApplication` (fournit `Greeter`) et `ModuleDonnees`
     (fournit `BddLocale`, seulement si Room+Hilt) ; **injection réelle**
     démontrée dans `BienvenueFragment`.
2. **targetSdk 34-36** (37 supprimé, défaut 36) + contraintes ERREUR
   `regle.targetSdk.sup.plateforme` (> plateformeMajeure 36) et
   `regle.minSdk.sup.targetSdk` ; compileSdk **verrouillé** à 36/0
   (règle 13, test `AlignementCatalogueTemplatesTest`).
3. **Extras sobres** : icône adaptative vectorielle maison
   (`ic_launcher*`, repli `mipmap-anydpi` + `mipmap-anydpi-v26`),
   thème nuit (`values-night/themes.xml`), règles de sauvegarde
   (`xml/backup_rules.xml` + `xml/data_extraction_rules.xml`), tout le
   contenu généré localisé via `{{t:}}` (fr/en parité vérifiée).
4. **i18n** : 19 clés ajoutées dans `i18n/fr.json` + `i18n/en.json`
   (`etape.*.titre`, `preset.*.nom/description`, `regle.*`, `hint.*`,
   `param.*.raison`, `tache.*`, `bienvenue.kdoc`, `main.navigation.kdoc`,
   `dao.compter.kdoc`) ; textes corrigés (`param.targetSdk.help` 34-36,
   `readme.sdk` compileSdk 36) ; nouveau `readme.navigation`.
5. **README exact** : arborescence fidèle aux fichiers réellement générés
   (icônes, values-night, xml/, navigation, Room, modules Hilt,
   ServiceCitations, BienvenueFragment).
6. **Wizard** (données, l'écran arrive en L5) : étapes
   plateforme/démarrage/bibliothèques/identité-application + préréglages
   `app-complete` et `app-reseau` ; groupes d'affichage
   (`demarrage`/`plateforme`/`bibliotheques`/`qualite`).
7. **Corrections bloquantes faites en avance de phase** :
   - `sansHilt = !avecHilt` ajouté aux calculés (le gabarit
     `MainActivity` l'utilisait mais il n'existait pas → la variante sans
     Hilt perdait l'import `AppCompatActivity` et `greeter`) ;
   - doublon de liaison Hilt supprimé (`ModuleDonnees` ne fournit plus
     `Greeter`, `ModuleApplication` seul fournisseur).

### android-library (templateVersion 1.2.0)

- `appName` dérivé (`defaultFrom: "appNameFromNom"`, ADR 0158 §2) ;
- orphelin `files/library/proguard-rules.pro.tpl` **supprimé** (non
  référencé par le manifeste, `consumer-rules.pro` seul utilisé) ;
- compileSdk 36/0 et build-tools 35.0.2 inchangés (alignés catalogue).

### Mutualisation (suite L3)

- `android-app` bascule `.gitattributes`/`.editorconfig`/wrapper/gradlew
  sur `commun:…` (ADR 0156) — attention : le motif AAPT2 exclut
  `_commun` du paquet d'assets (correctif v0.211.1, déjà en place).

## Ce qui reste à faire (dans l'ordre)

1. **Cloner les frères** (composite) si absents :
   ```bash
   cd /home/z
   git clone https://github.com/jjoblab/LayoutLib.git
   git clone https://github.com/jjoblab/codeide-tooling.git
   git clone https://github.com/jjoblab/codeide-lsp.git
   ```
2. **Compiler l'outil** (JDK 17 présent : `/home/z/tools/jdk-17` ; le
   script `scripts/env.sh` pointe `jdk-21` qui n'existe pas dans ce
   sandbox) :
   ```bash
   export JAVA_HOME=/home/z/tools/jdk-17
   ./gradlew :tools:generateur:compileKotlin -Pfreres.sources
   ```
   Réseau throttlé (429 Maven Central) → boucle de reprise :
   `/home/z/my-project/scripts/reprise-gradle.sh 8 :tools:generateur:compileKotlin -Pfreres.sources`
3. **`valider-manifestes` zéro écart** (portail L3-L5, ADR 0157) :
   ```bash
   java -jar tools/generateur/build/libs/generateur.jar valider-manifestes \
       --assets app/src/main/assets/templates
   ```
   (adapter au mode d'invocation réel de l'outil — voir
   `tools/generateur/src/main/kotlin/jo/codeide/tools/generateur/GenerateurModeles.kt`)
4. **verify-templates.sh** (builds réels, matrice dérivée) :
   ```bash
   scripts/verify-templates.sh
   # tranche Android seule : CODEIDE_COMBINAISONS='android' scripts/verify-templates.sh
   ```
   Vérifier en priorité les combinaisons critiques : Room+Hilt (liaison
   unique), Navigation×tiroir (graphe + NavigationUI), Retrofit×sans
   Coroutines (AVERTISSEMENT), `no-activity` (options désactivées avec
   raison), Java×tiroir×Hilt.
5. **Tests côté app** : `ModelesPhase2/3/4Test`, `ModelesEmbarquesTest`,
   `TemplatesIntegrationTest`, `AlignementCatalogueTemplatesTest`
   (les tests qui simulent la génération passent `appName` explicitement,
   la dérivation ADR 0158 ne les casse pas — à confirmer).
6. **Bump de version** `chore(release): v0.212.0 — lot L4…` (bump-version.sh)
   puis push du tag.
7. **Supprimer ce fichier** (`git rm docs/REPRISE_L4.md`) dans le commit
   de fin de lot.
8. Ensuite : **GO L5** (écrans, L5a-L5d — plan §9).

## Pièges connus

- **JAVA_HOME** : `scripts/env.sh` référence `/home/z/tools/jdk-21` qui
  n'existe pas dans ce sandbox → exporter `/home/z/tools/jdk-17` avant
  tout `./gradlew`.
- **`-Pfreres.sources` obligatoire** sinon « LayoutLib introuvable en
  binaire » (settings.gradle.kts:191) — ou jeton `github.packages.pat`.
- **PAT** (`ghp_…YoCu`) : révocable uniquement après L5 terminé.
- **429 Maven Central** : les échecs de résolution arrivent en « plugin
  not found » — c'est le réseau, pas la configuration ; refroidir 75-120 s
  et relancer (le cache Gradle conserve les artefacts déjà résolus).
- Les gabarits `{{#if avecNavigation}}` du tiroir gardent la liaison de
  vues (`liaison`) initialisée dans les DEUX branches.
- `ModuleApplication` est le SEUL fournisseur de `Greeter` — ne pas en
  remettre un dans `ModuleDonnees` (doublon de liaison Hilt).
