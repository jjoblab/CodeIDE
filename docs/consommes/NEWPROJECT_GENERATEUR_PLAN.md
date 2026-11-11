# Refonte du générateur de projets et des écrans « Nouveau projet » — plan d'exécution

> Document opérationnel du chantier (rôles et mode de travail : prompt
> « Refonte du générateur de projets et des écrans Nouveau projet » —
> dépôt `jjoblab/CodeIDE`). **Modèle d'après le mode de la mission IA** :
> ce fichier vit à la racine de `docs/` tant que des lots sont ouverts ;
> il rejoindra `docs/consommes/` à la livraison du lot L6 (règle §8
> d'`AGENTS.md`).
>
> État audité : commit `d9b0c615`, version **0.207.0** (branche `main`).
> ADRs du chantier : **0153** (contraintes déclaratives), **0154** (contrat
> d'affichage), **0155** (parcours adaptatif), **0156** (mutualisation),
> **0157** (outil et matrice), **0158** (manifestes homogènes).
> Maquettes : `docs/mockups/ui/newproject-flux-adaptatif.html`.

## 1. Mode de travail

Audit → conception → validation (lot L0, ce document) → lots L1 à L6 avec
« GO » explicite du propriétaire entre chaque lot. Aucun code de production
n'a été écrit pour L0. KDoc, commentaires, commits et documentation en
français ; commits conventionnels ; vérification selon
`docs/STRATEGIE_VERIFICATION.md` (qui prime) ; ADR numérotés à partir de
`0153` (`ls docs/adr` confirme que le dernier présent est `0152`).

## 2. Rapport L0 — confirmation des constats

Chaque constat du prompt (§4) a été re-vérifié sur le code au commit
`d9b0c615` avec preuve (fichier:ligne). Trois nuances factuelles sont
relevées (B1, B2.1, B3, C4) — aucune n'invalide l'esprit du constat.
**Bilan : 24/25 confirmés, 1 partiel, aucune infirmation majeure.**

### 2.1 Axe A — modèles et moteur

| Constat | Verdict | Preuve principale |
|---|---|---|
| A1.1 Retrofit sans permission `INTERNET` ni usage | **Confirmé** | `app/build.gradle.kts.tpl:63-66` ajoute les artefacts ; `AndroidManifest.xml.tpl` : 0 `<uses-permission>` dans tout `templates/` ; aucun code consommateur ; `ModelesPhase4Test:142-174` n'asserte que le Gradle |
| A1.2 Navigation sans graphe ni `NavHostFragment` | **Confirmé** | 0 `res/navigation/`, 0 `NavHostFragment` ; le tiroir `basic-activity` fait du `replace()` manuel (`MainActivityTiroir.kt.tpl:52-57`) |
| A1.3 Hilt : seule la classe Application | **Confirmé** | `Application.kt.tpl:9-10` (`@HiltAndroidApp`) ; 0 `@AndroidEntryPoint`/`@Inject`/`@Module` dans `tpl/android-app` |
| A1.4 Room généré mais jamais instancié | **Confirmé** | `BddLocale`/`TacheDao`/`TacheEntity` déclarés (`template.json:367-386`) ; 0 `Room.databaseBuilder` nulle part |
| A1.5 Coroutines sans exemple | **Confirmé** | seule la ligne Gradle ; 0 `Dispatchers`/`launch` dans `tpl/android-app` |
| A1.6 Spring : options Gradle-only, repo mémoire, yml minimal, Security verrouillante | **Confirmé** | `build.gradle.kts.tpl:22-34` (4 starters) ; `GreeterRepository.kt.tpl:14-27` en mémoire ; `application.yml.tpl` sans datasource ni `management.*` ; 0 `SecurityFilterChain` (le verrouillage 401 est même assumé par le README i18n `fr.json:53,56`) |
| A1.7 Contre-exemple KMP (options câblées) | **Confirmé** | `kotlin-multiplatform/template.json:188-202` : `ConfigurationSalutation.kt`/`DelaisSalutation.kt`/`HorodatageSalutation.kt` conditionnés par `when` |
| A2.1 Seul mécanisme : `visibleWhen` | **Confirmé** | `TemplateManifest.kt:38-50` (aucun `enabledWhen`/`requires`) ; `visibleWhen` utilisé seulement par kotlin-jvm/java (6×) |
| A2.2 `includeCoroutines` proposé en Java | **Confirmé** | `android-app/template.json:104-111` sans `visibleWhen` ; dépendance ajoutée hors plugin Kotlin |
| A2.3 `androidx-room-ktx` même en Java | **Confirmé** | `build.gradle.kts.tpl:71-75` : bloc `avecRoom` sans test `langageKotlin` |
| A2.4 `includeNavigation` avec `no-activity` | **Confirmé** | sans `visibleWhen` ; manifeste sans `<activity>` dans cette variante |
| A2.5 `targetSdk` 34–37 vs `compileSdk` 36 | **Confirmé** | `template.json:88-101` (choix 34-37, défaut 37) vs `build.gradle.kts.tpl:13-15` (`compileSdk = 36`) et `ToolchainCatalog.kt:102-106` (`platform@android-36`) ; `AlignementCatalogueTemplatesTest:55-74` verrouille 36/0 |
| A2.6 README « compileSdk 37.2 » | **Confirmé** | `android-app/i18n/fr.json:37` et `android-library/i18n/fr.json:28` (`readme.sdk`) rendu par `readme.md.tpl:10` |
| A2.7 Aucun avertissement de combinaison | **Confirmé** | `TemplateFormEvaluation` (`TemplateSummary.kt:40-44`) ne porte que `parameters/computedValues/isValid` |
| A3.1 Pas d'icône ni mipmap | **Confirmé** | 0 `android:icon`/`mipmap` dans `tpl/` |
| A3.2 Thème sans `values-night` | **Confirmé** | `themes.xml.tpl:4-9` ; 0 `values-night` généré |
| A3.3 `allowBackup="true"` sans règles | **Confirmé** | `AndroidManifest.xml.tpl:8` ; 0 `fullBackupContent`/`dataExtractionRules` |
| A3.4 Couleurs fixes | **Confirmé** | `colors.xml.tpl:3-9` (`violet_500`, `sarcelle_200` — valeur teal #FF03DAC5, nom francisé) |
| A4.1 Spring : Kotlin+Gradle figés, toolchain 17 | **Confirmé** | aucun choix `language`/`buildSystem`/`jdkVersion` ; `build.gradle.kts.tpl:14-16` `jvmToolchain(17)` |
| A4.2 `profiles.active: dev` sans profil | **Confirmé** | `application.yml.tpl:7-8` ; aucun `application-dev.yml` (19 fichiers déclarés) |
| A5 kotlin-jvm ≈ java | **Confirmé** | diff des manifestes : id, tags et 3 entrées de fichiers seulement ; 9 paramètres identiques caractère pour caractère |
| A6 `group` renseigné par 2/7 et jamais lu | **Confirmé** | `kotlin-jvm/template.json:155,161,167` ; copié par `TemplateEngine.kt:251-257` ; 0 lecteur (grep feature/app/use cases) |
| A7 wrapper ×8 + 5 textes ×7 + 2 orphelins | **Confirmé** | 8 copies 47 505 o, MD5 `0056…daf4` ; MD5 uniques pour `gradlew.tpl` etc. sur 7 copies ; orphelins `android-app/files/gradle-wrapper.jar` et `android-library/files/library/proguard-rules.pro.tpl` (croisement manifeste↔disque) |
| A8 `appName` figé sans dérivation | **Confirmé** | `android-app/template.json:17-25` (`"default": "MonApp"`), `android-library:17-35` (`"MaBibliotheque"`) ; seuls paramètres d'identité sans `defaultFrom` |

### 2.2 Axe B — écrans « Nouveau projet »

| Constat | Verdict | Preuve principale |
|---|---|---|
| B0.1 Parcours figé (5 étapes) | **Confirmé** | `EtatWizard.kt:55-62` (`ETAPES_WIZARD`) ; `when` exhaustif `NewProjectFragment.kt:175-194` ; le KDoc dit l'extension « par le développeur » |
| B0.2 `TemplateSection` = 2 valeurs | **Confirmé** | `ProjectTemplate.kt:97-103` ; usages parseur + feature |
| B1 Registres de présentation en dur | **Confirmé** (nuance) | `RenduParametres.kt:310-383` : 5 registres `when` sur valeurs globales avec `else -> valeur` brut ; nuance : `COMPOSANTS_NOMMES` (:462-467) est une map par **id de paramètre** (pas par valeur) ; `libelleValeur` = 7 branches JVM (`EtapeConfigurationFragment.kt:85-94`) |
| B2.1 Récapitulatif : labels au lieu de valeurs | **Confirmé** (nuance) | `EtapeRecapitulatifFragment.kt:139-143` joint `parametre.label` (résolu par le **moteur**, pas par un dictionnaire UI) ; interrupteurs désactivés inclus — le fond est exact |
| B2.2 « Contenu : fr » brut | **Confirmé** | `EtapeRecapitulatifFragment.kt:174` + `strings.xml:115` |
| B2.3 Puces Android brutes | **Confirmé** | `libelleValeur` 7 branches JVM ; puces montrent `empty-activity`, `kotlin`, `26`, `37` |
| B2.4 minSdk/targetSdk bruts | **Confirmé** | `RenduParametres.kt:295-306` : liste déroulante générique, aucun formatage « API 26 · Android 8.0 » |
| B2.5 Pastille modèle 40 dp/16 sp | **Confirmé** | `item_modele.xml:21-29`, `dimens.xml:8-9` ; monogramme « android » (7 car.) ≈ 56-60 dp > 40 dp : rognage probable (sans `autoSizeTextType`) |
| B2.6 `category` inutilisé | **Confirmé** | validé côté domaine (`TemplateManifestParser.kt:126`) mais absent de `TemplateSummaryUi` (`WizardViewModel.kt:170-175`) |
| B2.7 Recherche dès 5 modèles, pas de filtres | **Confirmé** | `EtapeModeleFragment.kt:94` (`SEUIL_RECHERCHE = 4`) ; catalogue = 7 ; aucun filtre/catégorie |
| B3 Configuration à plat | **Partiel** | 11 paramètres en liste plate confirmés (`etape_configuration.xml:22-31`, aucun titre de groupe, aucune zone Avancé) ; **infirmé** sur un point : chaque bibliothèque **a** une aide affichée (`RenduParametres.kt:168` + i18n) |
| B4 Fichiers identique pour tous, `.gitattributes` toujours | **Confirmé** | `FichierOptionnel` figé à 3 valeurs (`EtatWizard.kt:300-304`) ; `gitattributes.tpl` présent et non conditionné dans les 7 ; pas d'aperçu de licence |
| B5 Aucun aperçu avant l'étape 5 | **Confirmé** | `planifier()` appelé seulement à l'init-si-restaurant, à `avancer()` vers `RECAPITULATIF`, au `ReessayerPlan` et après renommage (`WizardViewModel.kt:393-424, 243, 555-585`) |
| B6 Aucune mémoire ni préréglage | **Confirmé** | seul stockage = `SavedStateHandle` du VM (mort avec le wizard) ; `AppSettings.defaultLicense` (MIT) **jamais lu** par la feature ; 0 préréglage |
| KEEP sans régression | **Confirmé** | `SavedStateHandle` (21 clés, `restaurer()`), dérivations figeables + resynchronisation, `RaisonValidation` typée → 13 messages localisés, vérification cible 400 ms, aperçu renommable ADR 0077 (5 tests), rollback `NonCancellable` (ADR 0023), `layout-sw600dp/fragment_newproject.xml`, animations réduites (`ANIMATOR_DURATION_SCALE`) |

### 2.3 Axe C — outil et vérification

| Constat | Verdict | Preuve principale |
|---|---|---|
| C1 Matrice à la main (Python intégré), 37 combinaisons | **Confirmé** | `verify-templates.sh:69-302` : 5 listes littérales (18+6+8+4+1 = 37), 0 lecture de manifeste ; ajout d'option = aucun signal ; couverture réelle : 8/12 288 pour `android-app` |
| C2.1 README « v0.10.0 » | **Confirmé** | `tools/generateur/README.md:3`, un seul commit depuis création ; `--generateur 0.10.0` (`:28`, script `:309`) |
| C2.2 `Module.md` = 2 modèles | **Confirmé** | `tools/generateur/Module.md:9-10,14` |
| C2.3 KDoc `Combinaisons.kt` = 2 modèles | **Confirmé** | `Combinaisons.kt:10` (le code n'impose pas la limite) |
| C2.4 En-tête « tous les triplets » | **Confirmé** | `verify-templates.sh:4-5` — vrai pour la paire JVM uniquement (12/12 triplets), faux pour les 5 autres familles |
| C3 CLI minimale | **Confirmé** | `GenerateurModeles.kt:149-166` (6 clés, inconnues ignorées silencieusement) ; sortie = `OK`/`ECHEC`/`GENERE` ; pas de lint, pas de matrice, pas de déterminisme |
| C4 Dépendance d'environnement | **Confirmé** (nuance doc) | défauts `/home/z/tools/jdk-17*`, `/home/z/tools/jdk-21`, `/home/z/tools/apache-maven-*`, `/home/z/android-sdk` (script `:25-35`), surcharge `CODEIDE_*` ; **nuance** : `docs/TEMPLATES.md` ne documente ni les variables ni la conduite à tenir si l'outillage manque (seule la panne réseau est traitée, `:284-285`) ; `setup-env.sh` n'installe ni JDK 17 ni Maven |

## 3. Tableaux de cohérence par modèle (cible après chantier)

Légende : **P** = pertinent (actif), **M** = masqué (`visibleWhen`),
**D+raison** = désactivé avec raison (`enabledWhen`), **AA** = ajouté
automatiquement (`autoDefinit`), **AV** = avertissement/info
(`constraint` AVERTISSEMENT/INFO). État cible après L3/L4/L5 — chaque ligne
correspond à une déclaration vérifiable par `valider-manifestes` (ADR 0157).

### 3.1 `android-app` (14 paramètres, cible)

| Option × contexte | Kotlin | Java | `no-activity` | Effet rendu garanti |
|---|---|---|---|---|
| `includeCoroutines` | P | **M** (langage) | P | exemple d'usage Kotlin câblé (`launch` + `Dispatchers`) |
| `includeRetrofit` | P | P | **D+raison** « nécessite un écran » | permission `INTERNET` + service d'exemple ; **AV** « pensez à Coroutines » si absent |
| `includeNavigation` | P | P | **D+raison** | `NavHostFragment` + graphe + destinations ; intégré au tiroir si `basic-activity` |
| `includeRoom` | P | P | P (hilt/simple) | base instanciée et utilisée ; **AA** KSP ; **AV** « Hilt recommandé » |
| `includeHilt` | P | P | P | `@AndroidEntryPoint` + module + une injection réelle ; **AA** KSP |
| `targetSdk` 34-37 | 34-**36** | 34-**36** | 34-**36** | **ERREUR** si > `plateformeMajeure` (36) ; défaut aligné 36 |
| `minSdk` > `targetSdk` | — | — | — | **ERREUR** `regle.target.sup.min` |
| `includeTests`/`includeWrapper` | P | P | P | inchangés (déjà effectifs) |

### 3.2 `spring-boot` (cible, L3)

| Option × contexte | Effet rendu garanti |
|---|---|
| `includeJpa` | entité + repository Spring Data + datasource H2 `application.yml`, remplacement du repo mémoire |
| `includeSecurity` | `SecurityFilterChain` explicite ; endpoint `health` ouvert ssi `includeActuator` ; tests verts dans les 16 combinaisons |
| `includeActuator` | exposition configurée (`management.endpoints.web.exposure`) |
| `includeValidation` | DTO validé + test du cas invalide |
| Nouveaux `langage`/`buildSystem`/`jdkVersion` | Kotlin/Java × Gradle/Maven × 17/21 — couverts par la stratégie `paires` (ADR 0157) |
| profils | `application-dev.yml`/`application-prod.yml` réels (défaut `dev`) |

### 3.3 Autres familles (cible)

| Modèle | Cohérence ajoutée |
|---|---|
| `kotlin-jvm` / `java` | paramètres conservés ; fichiers mutualisés (ADR 0156) ; regroupement affichage (Plateforme · Qualité) ; dérivation identité déjà saine |
| `android-library` | `minSdk`/`includeTests`/`includeWrapper` inchangés ; `appName` dérivé (ADR 0158) ; orphelin proguard soldé |
| `kotlin-multiplatform` | déjà câblé (contre-exemple) ; regroupement cibles/bibliothèques ; i18n de choix (ADR 0154) |
| `gradle-plugin` | 3 étapes (identifiants · plugin · qualité) ; rien d'ajouté sans preuve build |

## 4. Conceptions retenues (résumé — détail dans les ADR)

- **A-1** : `enabledWhen`+`disabledReasonKey`, `constraints` (ERREUR /
  AVERTISSEMENT / INFO) au mini-langage existant, `autoDefinit` (dépendances
  implicites, jamais d'écrasement d'un champ figé à la main), variable
  automatique `plateformeMajeure` (catalogue ADR 0089), sortie unique
  `EvaluateTemplateFormUseCase`, revalidation dans `CreateProjectUseCase`.
  → **ADR 0153**. Schéma : champs optionnels, `schemaVersion: 1` maintenu.
- **B-1** : libellés/sous-titres/descriptions/icônes **par choix** via
  convention de clés i18n (`param.<id>.choix.<valeur>.*`), indice de
  composant `rendu`, groupes (`groupe.*.titre`) et zone « Avancé »
  (`avance: true`) ; suppression des 5 registres + `libelleValeur` ;
  ADR 0021 amendé. → **ADR 0154**.
- **B-0** : bloc `wizard` du manifeste (étapes conditionnées par `quand`),
  socle imposé (MODELE · [étapes du modèle] · IDENTITE · FICHIERS ·
  RECAPITULATIF), `CalculeParcoursUseCase` (domaine), valeurs d'étapes
  disparues conservées mais exclues, registre de fragments au lieu du
  `when` exhaustif, aperçu en direct (feuille basse / panneau latéral,
  antirebond 300 ms, différentiel). → **ADR 0155**.
- **A-5** : `assets/templates/_commun/` + préfixe `commun:` résolu par les
  deux implémentations du port, gardes identiques ; gain chiffré ≈ 428 Ko
  d'APK ; orphelins supprimés. → **ADR 0156**.
- **C-1..C-4** : `lister` / `generer` / `matrice` (`exhaustif`/`paires`/
  `bornes`, dérivée des manifestes, JSON) / `valider-manifestes` /
  `determinisme`, protocole `PROTOCOLE=2`, script refondu (rapport Markdown
  + JUnit XML, message explicite si outillage manquant). → **ADR 0157**.
- **A-4/A-6/A-8** : `group` partout et exploité (étape Fichiers, récap) ;
  7ᵉ fonction `appNameFromNom` (nom → appName → packageName →
  applicationId) ; composition de manifestes écartée. → **ADR 0158**.

## 5. Parcours adaptatif par famille (B-0)

Schéma cible (`[ ]` = étape conditionnée par `quand` ; socle en majuscules).
Maquettes téléphone/tablette (y compris états désactivé, avertissement et
erreur) : `docs/mockups/ui/newproject-flux-adaptatif.html`.

```
android-app      MODELE → [Plateforme] → [Démarrage] → [Bibliothèques] → [Identité application] → IDENTITE → FICHIERS → RECAPITULATIF
android-library  MODELE → [Bibliothèque] → IDENTITE → FICHIERS → RECAPITULATIF
spring-boot      MODELE → [Langage et build] → [Modules] → [Sécurité et exploitation] → IDENTITE → FICHIERS → RECAPITULATIF
kotlin-jvm/java  MODELE → [Type et build] → IDENTITE → FICHIERS → RECAPITULATIF        (≈ parcours actuel)
kmp              MODELE → [Cibles et bibliothèques] → IDENTITE → FICHIERS → RECAPITULATIF
gradle-plugin    MODELE → [Identifiants du plugin] → IDENTITE → FICHIERS → RECAPITULATIF
```

- « Démarrage » (android-app) : `projectType` + `language`, avec
  conséquences affichées (no-activity désactive Retrofit/Navigation —
  raison visible). « Bibliothèques » n'existe que si au moins une
  bibliothèque est proposée ; les réglages ouverts par un coche (nom de
  base Room, URL de base Retrofit, mode d'authentification Security côté
  Spring) apparaissent **au bon endroit** (dans la même étape).
- L'étape IDENTITE porte nom/description/emplacement (+ dérivés
  resynchronisables `appName`/`packageName`/`applicationId` pour Android) ;
  FICHIERS ne propose que les groupes et extras du modèle ; RECAPITULATIF
  s'organise selon les étapes réellement parcourues, avec « Modifier » par
  étape, bibliothèques listées, avertissements restants et différentiel de
  fichiers.

## 6. Refonte complète vs évolution par étapes (choix argumenté)

**Choix retenu : évolution encadrée du module existant** (pas de réécriture
de `feature/newproject`), en sous-lots livrables.

| Critère | Évolution encadrée (retenue) | Refonte complète |
|---|---|---|
| Socle à conserver | machine à états `SavedStateHandle`, dérivations resynchronisables, validation typée, emplacement éphémère ADR 0022, aperçu renommable ADR 0077, rollback ADR 0023 — **audités présents et testés** (§2.2 KEEP) | tout à réétablir, y compris les 37 scénarios de mort du processus |
| Tests | 37 `WizardViewModelTest` + `ArborescenceTest` + `NewProjectFragmentTest` majoritairement réutilisables (renommages mécaniques d'ids socle) | réécriture complète de 1 341 lignes de tests |
| Risque | localisé (EtatWizard, NewProjectFragment, RenduParametres) | étendu (hôte, navigation, restauration) |
| Cohérence ADR | 0020 amendé (la liste cesse d'être constante — l'ADR prévoyait déjà « liste configurable ») | ADR 0020 révoqué, retour en arrière impossible |
| Coût | L5 découpé en L5a-L5d | plus long, GO monolithique |

Le `WizardViewModel` (868 lignes) est le seul fichier à retravailler en
profondeur (état dériv du parcours, aperçu en direct, exclusion des étapes
absentes) — sa structure d'actions/actions→états est conservée.

## 7. Nouveaux paramètres et extras proposés (A-3, chiffrés)

| # | Élément | Modèle | Valeur | Coût | Preuve build |
|---|---|---|---|---|---|
| 1 | `langage` (Kotlin/Java) | spring-boot | CHOICE, défaut `kotlin` | moyen (miroirs `.java` du template existant réutilisables) | oui — paires × build |
| 2 | `buildSystem` (Gradle/Maven) | spring-boot | CHOICE, défaut `gradle` | moyen (pom complet à écrire) | oui — paires × build |
| 3 | `jdkVersion` (17/21) | spring-boot | CHOICE, défaut 17→21 | faible | oui — déjà prouvé JVM |
| 4 | Profils `dev`/`prod` réels | spring-boot | défaut sobre (2 yml) | faible | oui |
| 5 | Icône adaptative vectorielle maison | android-app/library | défaut sobre (pas d'option) | moyen (drawables + manifeste) | oui — APK |
| 6 | Thème clair/sombre (`values-night`) | android-app/library | défaut sobre | faible | oui |
| 7 | Règles de sauvegarde (`backup_rules`/`data_extraction_rules`) | android-app/library | défaut sobre | faible | oui |
| 8 | Localisation du contenu généré selon `contentLanguage` | android-app/library | défaut sobre | faible | oui |
| 9 | Workflow GitHub Actions **adapté au type** (JVM/Android/Spring/KMP/plugin) | tous | extra opt-in (étape Fichiers) | moyen (1 gabarit par famille, cohérent avec la CI CodeIDE : spotless/detekt/tests) | structurel + lint ; build non prouvé en sandbox (signalé) |
| 10 | `Dockerfile` | spring-boot | extra opt-in | faible | structurel (lint) |
| 11 | `.gitattributes` choisissable | tous | extra opt-in (aujourd'hui toujours) | faible | structurel |
| 12 | Coroutines → usage réel Kotlin | android-app | câblage du #A1.5 | faible | oui |
| 13 | Room → base instanciée (via Hilt si coché) | android-app | câblage du #A1.4 | moyen | oui (test unitaire généré) |
| 14 | Retrofit → permission + service d'exemple | android-app | câblage du #A1.1 | faible | oui |
| 15 | Navigation → graphe + `NavHostFragment` | android-app | câblage du #A1.2 | moyen | oui |
| 16 | Hilt → `@AndroidEntryPoint` + module + injection | android-app | câblage du #A1.3 | moyen | oui |

Retenus pour L3/L4 par défaut : 1-8, 11-16 (tous prouvables par build réel
ou lint structurel) ; 9-10 opt-in si le temps le permet. Rien d'autre sans
preuve (règle 9, §6 du prompt : aucun gonflement gratuit).

## 8. Réponses recommandées aux questions à trancher (§9)

1. **Schéma du manifeste** : champs optionnels, `schemaVersion: 1` maintenu
   (recommandation suivie — ADR 0153 §1).
2. **Nouveaux paramètres** : liste §7 — seulement ce que la chaîne peut
   prouver ; on commence par Spring Boot (langage/build/JDK/profils) et
   l'icône/thème Android.
3. **Mutualisation** : oui, gain chiffré ≈ 428 Ko d'APK, gardes inchangées
   (ADR 0156).
4. **Préréglages et mémoire** : préréglages oui (bloc `presets`, ADR 0155) ;
   mémoire des derniers choix en L6 si le temps le permet (port de réglages
   existant).
5. **CI GitHub Actions et Dockerfile** : opt-in à l'étape Fichiers
   (extras #9-10).
6. **`targetSdk` > compileSdk (36)** : les `choices` Android sont restreints
   à 34-36 (défaut 36, README corrigé) **et** une règle `ERREUR`
   (`targetSdk > plateformeMajeure`) sert de filet — recommandation suivie
   (ADR 0153 §3).
7. **Bibliothèques** : avec le parcours adaptatif, **étape dédiée déclarée**
   par les modèles qui ont plus de ~4 options (android-app, spring-boot) ;
   simple groupe sinon (ADR 0155).
8. **Ampleur de la refonte** : évolution encadrée (§6) — l'état unique
   `EtatWizard` et la machine à étapes sont solides ; chaque famille
   obtient son parcours validé sur maquette avant implémentation.
9. **Mode rapide** : oui, en L5d (préréglage puis récapitulatif), après le
   parcours complet.

## 9. Plan des lots

| Lot | Contenu | Vérifications clés |
|---|---|---|
| **L0** (livré) | Rapport (§2-§8), ADR 0153-0158, maquettes | relecture propriétaire |
| **L1 — Socle moteur** | Schéma étendu (0153), évaluation contraintes/désactivés dans `EvaluateTemplateFormUseCase`, revalidation `CreateProjectUseCase`, libellés de choix déclaratifs (0154), groupes, `appNameFromNom` (0158) | `core:model`+`core:domain` (+tests, Kover ≥ 80 %), chaîne RACINE (port `core:*` touché) |
| **L2 — Outil et matrice** | 5 commandes (0157), lint, matrice dérivée, script refondu, doc outil à jour | tests générateur + propriété pairwise ; `verify-templates.sh` vert (outillage requis, sinon signalé) |
| **L3 — Modèles hors Android** | spring-boot (langage/build/JDK/profils + câblage JPA/Security/Actuator/Validation), kotlin-jvm/java (groupes, i18n de choix), kmp (i18n), gradle-plugin (étapes, i18n) ; mutualisation 0156 ; templateVersion ++ | lint + matrice `paires` par modèle ; build réel au minimum pairwise |
| **L4 — Modèles Android** | android-app (câblage 12-16, targetSdk 34-36, readme exact), android-library (appName dérivé, orphelin soldé), icône/thème/backup/localisation ; compileSdk verrouillé (règle 13) | idem L3 + `AlignementCatalogueTemplatesTest` |
| **L5 — Écrans** (livré, v0.213.0) | L5a socle parcours dynamique (ADR 0155, état), L5b étapes communes (IDENTITE/FICHIERS adaptés/RECAPITULATIF fidèle), L5c étapes par famille + états D/AV/ERREUR, L5d aperçu en direct + tablette (+ mode rapide) | tests existants verts (renommages mécaniques tracés) + nouveaux (parcours, préréglages, `CalculeParcoursUseCaseTest`) ; fr/en parité ; checkUiTokens tenu (cliquet descendu) ; recette `docs/TESTS_MANUELS.md` W19-W31 |
| **L6 — Finitions** | dette `checkUiTokens` du module réduite (cliquet `-Pui.baseline.ratchet`), doc module/ADR 0021 amended consigné, `docs/consommes/` | chaîne complète, CI verte, tag |

## 10. Écarts, risques et prochaine étape

> **Statut 2026-11-11 (lot L5 livré, v0.213.0)** : les quatre sous-lots
> L5a-L5d sont implémentés (parcours adaptatif consommé par l'état et les
> écrans, étapes communes, rendu enrichi ADR 0154, aperçu en direct +
> mode rapide). Reste au lot **L6** : dette `checkUiTokens` du module
> (cliquet `-Pui.baseline.ratchet`), doc module/ADR 0021 amended consigné,
> `docs/consommes/`. Captures d'écran du parcours : à produire à la
> recette sur appareil (recette manuelle W19-W31).
>
> **Statut final 2026-11-11 (lot L6 livré, v0.214.0)** : dette
> `checkUiTokens` du module **soldée** — 6 occurrences corrigées (2× R4
> `etape_fichiers.xml` → `Widget.CodeIDE.Button.Outlined`, 1× R5
> `vue_ligne_arborescence.xml` → `Widget.CodeIDE.Button.Icon`, 3× R7
> rayon/élévation → jeton `@dimen/elevation_aucune`), les 5 entrées du
> module sortent de la baseline du cliquet (24 → 19 fichiers) ;
> ADR 0021 amended consigné (note d'amende ADR 0154 en fin d'ADR) ;
> `feature/newproject/Module.md` à jour du parcours adaptatif. Le
> chantier **L0-L6 est terminé** : conformément à la règle §8
> d'`AGENTS.md`, ce document rejoint `docs/consommes/` et ne se modifie
> plus. Seule la recette manuelle W19-W31 (captures d'écran du parcours
> sur appareil) reste au propriétaire, hors lots.

- **Écart d'environnement (signalé, sans contournement)** : cet environnement
  de travail n'a ni JDK 17, ni Maven, ni SDK Android, ni le clone frère
  `../LayoutLib` requis par la configuration Gradle ; la chaîne locale
  Gradle n'a pas pu être exécutée pour L0. `scripts/modules-touches.sh`
  renvoie **rien** pour des changements `docs/**`/`*.md` (aucun module
  touché), la CI GitHub porte donc la vérification complète au push.
- **Risque principal** : la modification de `TemplateFormEvaluation`
  (L1) touche un port public de `core:model` consommé par `feature:newproject`
  — chaîne RACINE obligatoire dès L1 (STRATEGIE_VERIFICATION §3).
- **Risque documentaire** : `docs/TEMPLATES.md` (contrat), le tableau des
  versions et l'en-tête du script évolueront en L2/L3/L4 — le contrat des
  concepteurs s'élargit (choix, groupes, étapes, préréglages) : mise à jour
  **au même lot** que chaque extension.
- **Prochaine étape** : relecture de ce rapport et des ADR par le
  propriétaire, arbitrages éventuels (§8), puis **« GO L1 »**.
