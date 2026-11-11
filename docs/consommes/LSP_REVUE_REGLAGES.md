# Revue du LSP après séparation et plan des réglages

> Analyse de `jjoblab/codeide-lsp` (v1.0.2, commit `fc82fb3`) et de son usage par CodeIDE
> (v0.134.44, commit `3c57ee4`). Chaque constat vient de la lecture du code ; ce qui reste
> incertain est marqué « **à vérifier** ». Date : 2026-10-17. ADR associé : 0128.
>
> **Mise à jour 2026-10-20** : `codeide-lsp` a publié **v1.1.0** le 2026-10-19 (lot de
> stabilisation S3 — renommage borné et formateur XML, correctifs Kotlin KP8/KP1, notices
> P3, purge provenance ADR 0121 côté frère). Les tables §1/§4 reflètent encore v1.0.2 :
> le moteur XML gagne désormais le **renommage** et le **formatage**. La release du lot
> LSP-1 vise **v1.2.0** (v1.1.0 est prise).

## 1. État après séparation

| Module `codeide-lsp` | Nature | Rôle | Fichiers |
|---|---|---|---|
| `:lsp:host` | Android (AAR) | **Hôte** : `SessionLsp`, orchestrateur sync → modèle → index → moteurs | 24 `.kt` |
| `:lsp:java` | Android (AAR) | Moteur Java (ecj patché pour ART, lsp4j) | 109 `.java` |
| `:lsp:kotlin` | JVM pur | Moteur Kotlin, grammaire vendorée, suite de parité | 77 `.kt` |
| `:lsp:xml` | JVM pur | Moteur XML Android tolérant, zéro dépendance | 56 `.kt` |
| `:lsp:index` | JVM pur | Index disque en segments par jar (ADR 0111) | 31 `.kt` |
| `:lsp:model` | JVM pur | Snapshots immuables du projet | 11 `.kt` |
| `:lsp:ecj-art`, `:lsp:testing` | build-time / tests | Patcheur ASM ; suite d'intégration | — |

- CodeIDE consomme `com.github.jjoblab.codeide-lsp:*` **1.0.2** (catalogue de versions) ; en dev,
  le dépôt frère est intégré en source (`lsp.dir`, `CODEIDE_LSP_DIR`, défaut `../codeide-lsp`).
- `app` et `feature:editor` ne voient que `:lsp:host`, qui **ne dépend d'aucun `core:*`** : frontière nette.
- `kotlin` et `xml` sont des entités indépendantes (SPI `ServiceLoader`), pensées comme **packs langue**
  téléchargeables (décision D6, différée).
- Dépendance en chaîne : `lsp:model` → `codeide-tooling:protocol` (autre dépôt frère).

**Capacités annoncées par chaque serveur** (lues dans le code) :

| Capacité | Java | Kotlin | XML |
|---|:-:|:-:|:-:|
| Complétion, survol, définition, symboles de document, repli (folding), actions rapides | ✔ | ✔ | ✔ |
| Références, renommage, aide à la signature, surlignage | ✔ | ✔ | — |
| Formatage (document/plage), indices en ligne (inlay), jetons sémantiques, hiérarchies, code lens, implémentations | ✔ | — | — |

## 2. Ce que l'application expose aujourd'hui

- **Un seul réglage lié au LSP** : « Badges de diagnostic » (`AppSettings.editorChipsDiagnostics`).
- **Indexation** : pastille de toolbar + bottom sheet de détail (ADR 0117 ; `fragment_detail_indexation.xml`,
  `PresentationIndexation`), `Réindexer` / `Réessayer les échecs`.
- **Aucune** entrée `SectionParametres` pour le LSP, aucune chaîne de réglage, aucun écran d'état des moteurs.

## 3. Constats

| # | Constat | Gravité |
|---|---|---|
| L1 | **Aucune surface de configuration.** `SessionLsp` n'accepte que `stockageIndex`, `dispatcheurIo`, `lecteurStats`, `jdkHome`, `androidJar`, `parentJob`, `surveillanceFichiersMs`. Budget du cache (auto), délai d'analyse Java (50 ms), plafonds ecj (8 192 entrées, 24 jars ouverts, parse cache 8), plafond de correctifs Kotlin (8) sont des **constantes**. Un écran de réglages *honnête* (chaque contrôle projette une API réelle, principe ADR 0059) est impossible sans une API de configuration. | 🟠 Bloquant pour les réglages |
| L2 | **Documentation contradictoire.** `README.md` de `codeide-lsp` annonce un moteur « Kotlin K2 » ; `settings.gradle.kts` de CodeIDE parle de « LSP Kotlin (K1, ADR 0115) ». Le README annonce aussi la publication « à venir » alors que CodeIDE consomme déjà 1.0.2 par coordonnées. **À vérifier** laquelle est exacte, puis aligner. | 🟠 |
| L3 | **Règle 13** : les titres de `docs/LSP_KOTLIN.md` et `docs/LSP_XML.md` (dans **les deux dépôts**) citent encore une étude d'un projet tiers du même type. À purger (titre + corps), tests/doc compris. | 🟠 Conformité |
| L4 | **Numérotation d'ADR dupliquée** : `codeide-lsp/docs/adr` contient 0109–0120 (copie de la numérotation de CodeIDE, 0119/0120 existent dans les deux) ; CodeIDE est déjà à 0121–0128. Les prochains ADR risquent de **collisionner**. | 🟡 |
| L5 | **Docs dupliquées** : `LSP_JAVA.md`, `LSP_KOTLIN.md`, `LSP_XML.md` existent dans les deux dépôts ; elles vont diverger. | 🟡 |
| L6 | **Estimation du temps restant d'indexation** : l'ADR 0117 laissait sa fiabilité à trancher ; aucun réglage ne doit l'exposer tant que ce n'est pas fait. | 🟡 |
| L7 | **Mémoire/stockage invisibles** : aucune vue de la taille du cache d'index sur disque ni action « vider », alors que l'appareil est contraint (4 Go de RAM côté dev, stockage partagé). | 🟡 |

**Points forts** : hôte indépendant de l'application ; état d'indexation riche (phases, bilan,
durées, avertissements) ; persistance du modèle (`lsp-modele.json`) ; surveillant de fichiers ;
moteurs Kotlin/XML sans dépendance interne.

## 4. Réglages envisagés et leur ancrage

| Contrôle (écran) | Ancrage | Statut |
|---|---|---|
| Moteurs : état Java / Kotlin / XML (prêt, indexation, partiel, erreur) | `SessionLsp.etatIndexation`, `SessionLspKotlin`/`Xml` | **Existe** |
| Réindexer · Réessayer les échecs | `SessionLsp.reindexer()` | **Existe** (« réessayer les échecs » : exposé par l'état, à confirmer) |
| Détection des modifications externes (activer, période 2/5/10/30 s) | `surveillanceFichiersMs` du constructeur | **Existe** (prise en compte à la réouverture ; à rendre dynamique) |
| Taille du cache d'index sur disque (affichage) | `stockageIndex` + calcul | **À créer** côté hôte (`tailleCacheOctets()`) |
| Budget maximal du cache (Auto / 256 / 512 / 1024 Mo) | `budgetCacheOctets` d'`ServiceIndexDisque` | **À créer** (plomberie `ConfigurationLsp`) |
| Vider le cache d'index | — | **À créer** (`viderCache()`, refusé pendant une indexation) |
| Fonctions par langage (complétion, survol, diagnostics, signatures, renommage, actions rapides, formatage, indices en ligne) | **liste dérivée des capacités annoncées** ; interrupteurs appliqués **côté client** (enregistrement des fournisseurs de l'éditeur) | **À créer** côté CodeIDE ; **aucun** interrupteur pour une capacité que le serveur n'annonce pas |
| Gravité affichée des diagnostics (erreurs seules / + avertissements / tout) | filtre côté client | **À créer** côté CodeIDE |
| JDK utilisé · plateforme Android (`android.jar`) | `jdkHome`, `androidJar` | **Lecture seule** (fournis par l'environnement ; lien vers Environnement) |
| Réactivité de l'analyse (Réactive / Équilibrée / Économe) | débounce et plafonds | **Bientôt** (nécessite une API moteur ; affiché avec la puce « Bientôt » existante) |
| Packs de langue téléchargeables (Kotlin, XML) | décision D6 différée | **Bientôt** |
| Copier le diagnostic LSP (expurgé) | état + `LogRedactor` | **À créer** côté CodeIDE (modèle : « Copier le diagnostic » de l'Environnement) |

## 5. Plan d'exécution

| Lot | Dépôt | Contenu | Dépend de |
|---|---|---|---|
| **LSP-0** | `codeide-lsp` + CodeIDE | Purge règle 13 (L3) ; trancher K1/K2 et publication (L2) ; docs LSP : une seule source (L5) ; règle de numérotation d'ADR (L4) | — |
| **LSP-1** | `codeide-lsp` | API : `ConfigurationLsp` (données immuables, défauts), `SessionLsp(config)` + `appliquer(config)` pour le sous-ensemble dynamique, `tailleCacheOctets()`, `viderCache()`, exposition des capacités par langage ; tests ; **release v1.2.0** (v1.1.0 publiée le 2026-10-19 par la stabilisation S3) | LSP-0 |
| **LSP-2** | CodeIDE | Montée de version (**version vérifiée**), `LspSettings` + dépôt dédié (hors `AppSettings`), câblage dans `CoordonnateurLsp`, filtres côté client | LSP-1 |
| **LSP-3** | CodeIDE | Écrans L1–L5 (`docs/consommes/LSP_SPEC_ECRANS.md`) construits **uniquement** avec les composants d'ADR 0127 | ✅ livré v0.161.0 (dépendances : LSP-2, UI-1, UI-2) |
| **LSP-4** | CodeIDE | « Copier le diagnostic LSP », tests d'architecture (aucun interrupteur sans capacité), accessibilité, EN/FR, `TESTS_MANUELS.md` | ✅ livré v0.162.0 (lien L3 « Détails de l'indexation » par demande-état sur `PortLsp` + `revenirALEditeur` ; `ArchitectureInterrupteursLspTest` ; audit a11y § 5 ; EN/FR paritaires ; « Copier le diagnostic » livré par LSP-3 — L5) |

Les écrans de réglages **n'affichent jamais un contrôle sans effet** : ce qui n'est pas ancré est
masqué ou marqué « Bientôt ».
