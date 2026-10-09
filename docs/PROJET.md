# Spécification Projet — section « Projet » du tiroir CodeIDE

Référence de comportement : Android Studio (Project Structure →
Dependencies, Build Variants, fenêtre Gradle).

## 1. Périmètre

La section « Projet » du tiroir (5e destination du rail) gère :
dépendances déclarées et résolues, mises à jour, catalogue de versions,
variantes de build, tâches Gradle. **Hors périmètre** : gestionnaire de
SDK, résolution hors ligne, dépôts privés, signature, vulnérabilités.

## 2. Architecture

- **Protocole v7** : nouveau `ResolvedDependenciesRequest`/`Result`
  (arbre avec transitives, versions, raisons) + `BuildVariantsRequest`/`Result`.
- **Lecteur de scripts** : module pur JVM (Kotlin DSL, Groovy, TOML),
  lecture via FUSE.
- **Client HTTP** : `maven-metadata.xml` pour les versions disponibles.
- **Tâches** : réutilise `FeuilleTachesFragment` existant.

## 3. Écrans

### 3.1 Onglet « Dépendances déclarées »

Sélecteur de module (liste) → liste des dépendances triées par
configuration puis nom. Chaque ligne : icône (bibliothèque/module/jar),
`groupe:nom`, version, configuration. Toucher → feuille de détails :
version actuelle, versions disponibles, configuration, Aller au fichier,
Supprimer (confirmation). Ajout (+) : bibliothèque (recherche),
module, jar local. Après écriture : bandeau « Synchroniser ».

### 3.2 Onglet « Dépendances résolues »

Arbre par configuration/variante. Bascule arbre/liste. Recherche.
Conflits mis en évidence (version demandée ≠ retenue). Vue inverse
« qui dépend de ça ». Copie des coordonnées. Chargement paresseux.

### 3.3 Onglet « Mises à jour »

Indicateur par dépendance. Choix d'une version proposée. Mise à niveau
groupée avec aperçu. Avertissement pour versions dynamiques (`+`).
Support `libs.versions.toml` : alias, références partagées.

### 3.4 Onglet « Variantes »

Sélecteur par module (debug/release, product flavors, build types).
Variante mémorisée par projet, réellement utilisée par les builds et
les classpaths LSP.

### 3.5 Onglet « Tâches »

Arbre par module puis par groupe. Recherche. Lancement (réutilise
l'exécution et la console existantes). Tâches récentes et favorites.
Réutilise `FeuilleTachesFragment`.

## 4. Critères d'acceptation

1. Dépendances déclarées lues depuis les scripts de build et le catalogue. **✅ P3 livré** — `ParseurDependances` (regex sur `implementation/api/...`).
2. Dépendances résolues avec arbre, transitives, versions, raisons. **⏳ P4 protocole prêt** — `ResolvedDependenciesRequest/Result` (v7), handler serveur en stub.
3. Modification des scripts (ajout/suppression/montée de version) sûre. **⏳ À venir** — édition syntaxique minimale.
4. Versions disponibles depuis Maven (cache, hors ligne). **⏳ À venir** — ADR HTTP séparé requis (cf. ADR 0095 §4).
5. Variantes sélectionnables et réellement utilisées. **⏳ P5 protocole prêt** — `BuildVariantsRequest/Result` (v7), handler serveur en stub (sans AGP TAPI branché).
6. Tâches Gradle parcourables et lançables. **✅ P5 livré** — bouton d'ouverture de `FeuilleTachesFragment` (réutilisé).
7. Aucune régression sur le tooling, l'éditeur, les autres fragments. **✅** — chaîne CI verte.

## 5. Avancement

| Étape | Statut | Livrable |
|-------|--------|----------|
| P0 | ✅ Livré | ADR 0095, spec PROJET.md, maquette projet.html |
| P1 | ✅ Livré | Protocole v7 : `BuildScriptsRequest/Result` + `BuildScriptsHandler` serveur + `LecteurScriptsDeBuild` JVM |
| P2 | ✅ Livré | Fragment `ProjetFragment` + `ProjetViewModel` + port `GradleToolingRepository.scriptsBuild` + 5e destination du rail |
| P3 | ✅ Livré | Onglet Dépendances : `ParseurDependances` (regex implementation/api/etc.) + UI |
| P4 | ✅ Livré | `ResolvedDependenciesRequest/Result` (v7) + handler branché à `IdeaProject` (dépendances directes, transitives à venir P6+) |
| P5 | ⏳ Protocole prêt | `BuildVariantsRequest/Result` (v7) + handler stub + onglet Tâches (FeuilleTachesFragment). Branchement AGP TAPI nécessite ajout `com.android.tools.build:gradle-api` (~10 Mo) au serveur — décision reportée |
| P6 | ✅ Port + impl livrés | `MavenVersionesDisponibles` (port) + `ClientMavenHttp` (impl HttpURLConnection) + ADR 0097. UI onglet « Mises à jour » à venir |
| P6+ | ⏳ À venir | Édition des scripts, dépendances transitives (`dependencyInsight`), UI onglet « Mises à jour » |
