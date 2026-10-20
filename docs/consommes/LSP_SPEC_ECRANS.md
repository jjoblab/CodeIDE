# Réglages LSP — spécification des écrans (« Intelligence du code »)

> **Mise à jour 2026-10-21 (livraison LSP-4, v0.162.0) — document CONSOMMÉ** :
> les trois écarts sont clos — (3) le lien « Détails de l'indexation » est
> LIVRÉ : la rangée L3 pose une demande-ÉTAT sur `PortLsp`
> (`demandeDetailsIndexation`), l'espace éditeur la consomme à sa reprise
> (collecte STARTED) et ouvre le bottom sheet de l'ADR 0117 — la
> remontée passe par `AppNavigator.revenirALEditeur` (miroir ADR 0048).
> Le test d'architecture « aucun interrupteur sans capacité annoncée »
> (`ArchitectureInterrupteursLspTest`) verrouille la dérivation des rangées
> L2 ; l'accessibilité § 5 est auditée (portée par les composants ADR 0127)
> et la recette vit dans `TESTS_MANUELS.md` § LSP-4. Les écarts (1)
> interrupteurs « Bientôt » (décision LSP-2, chantier code-editor) et
> (2) rangée « Version » omise (`:lsp:host` n'expose pas de version)
> restent assumés. Toutes les étapes de ce document sont livrées : il
> rejoint `docs/consommes/` (AGENTS.md §8).

> **Mise à jour 2026-10-20 (livraison LSP-3, v0.161.0)** : les cinq écrans sont
> livrés ; trois écarts assumés y sont consignés — (1) les interrupteurs de la
> carte Fonctions et « Gravité affichée » restent des puce « Bientôt » tant que
> les filtres exigent une évolution de la bibliothèque code-editor (décision
> LSP-2, jamais un contrôle sans effet) ; (2) la rangée « Version » du moteur
> (L2) est omise : `:lsp:host` n'expose aucune version de moteur ; (3) le lien
> « Détails de l'indexation » (L3) est différé à LSP-4 (bottom sheet `internal`
> à feature:editor). L'accessibilité (§ 5) est portée par les composants ;
> la revue sur appareil vit dans `TESTS_MANUELS.md` § LSP-3.

> Complète l'ADR 0128 et la maquette `docs/mockups/lsp/lsp-reglages.html` (L1…L5). **Tous les
> écrans sont assemblés avec les composants de l'ADR 0127** — aucune dimension, couleur, taille
> de texte ni style de widget n'est défini ici ou dans la feature : on les emprunte à `core:ui`.
> Dépend des lots **UI-1 et UI-2** et de **LSP-1/LSP-2**.

## 1. Navigation

- `SectionParametres.LANGAGES` (« Intelligence du code », icône `ic_code`/équivalent à vérifier
  dans `core:ui`), 2ᵉ rangée du groupe « Éditeur » dans le maître (`SettingsFragment`).
- Écrans = fragments de la section dans `feature:settings` (aucune nouvelle Activity), même
  gabarit : `MaterialToolbar` `Widget.CodeIDE.Toolbar` (retour), `ScrollView`, cartes.
- Fil : **L1** → **L2** (×3 langages) · **L3** · **L4** · **L5**.

## 2. Gabarit commun (à ne pas réinventer)

```
LinearLayout vertical (fond ?attr/colorSurface)
├─ MaterialToolbar  style=@style/Widget.CodeIDE.Toolbar  (navigationIcon=homeAsUp, title=@string/lsp_*_titre)
└─ ScrollView (fillViewport)
    └─ LinearLayout vertical
        └─ MaterialCardView  style=@style/Widget.CodeIDE.Card  (marges @dimen/spacing_md / spacing_sm)
            ├─ TextView  style=EnteteSection            ← libellé de la carte
            └─ RangeeParametre …                         ← une par réglage
```
Chaque écran gère 4 états via `VueEtat` : **chargement** (état d'indexation inconnu), **vide**
(aucun projet ouvert → les moteurs sont inactifs : message + « Ouvrir un projet »), **erreur**,
**contenu**. Les valeurs « temps réel » (état des moteurs) sont **observées** (`StateFlow`),
jamais lues une fois.

## 3. Écrans

### L1 — Vue d'ensemble (`fragment_lsp_apercu.xml`)
| Carte | Contenu |
|---|---|
| **Moteurs** | 3 rangées `RangeeParametre(type=chevron)` : Java · Kotlin · XML. Sous-titre = état court (« Prêt », « Indexation 62 % », « Partiel », « Erreur »). Pastille `IndicateurEtat` à droite (ok / avertissement / erreur / neutre — **forme + texte**, pas la couleur seule). Appui → L2. |
| **Index et cache** | Rangée chevron → L3 ; sous-titre « 184 Mo · mis à jour il y a 2 h ». |
| **Environnement d'analyse** | Rangée chevron → L4 ; sous-titre « JDK 17 · plateforme API 34 » (valeurs lues, jamais saisies). |
| **État et diagnostic** | Rangée chevron → L5. |
| Note | Texte `BodySmall` : « Ces réglages s'appliquent au projet ouvert. Certains prennent effet à la réouverture. » |
**Sans projet ouvert** : les pastilles passent en `neutre` « Inactif » ; les réglages restent éditables.

### L2 — Langage (`fragment_lsp_langage.xml`, **un seul layout** pour Java/Kotlin/XML)
Titre = nom du langage ; argument de navigation = identifiant du langage.
| Carte | Contenu |
|---|---|
| **Moteur** | Rangées `type=aucun` (lecture) : Nom du moteur · Version · État. Pour Kotlin/XML, puce « Pack langue — bientôt » (non interactive). |
| **Fonctions** | **Générée dynamiquement** depuis `capacites(langage)` : une `RangeeParametre(type=interrupteur)` par capacité annoncée **et** consommable par l'éditeur. Ordre fixe : Complétion · Survol · Diagnostics · Aide à la signature · Définition/Références · Renommer · Actions rapides · Formatage · Indices en ligne. Sous-titre = effet en une phrase. |
| **Diagnostics** | Rangée `type=valeur` « Gravité affichée » : Erreurs seules / Erreurs et avertissements / Tout. Le texte précise : « Les erreurs de compilation restent toujours visibles. » |
| **Réactivité** | Rangée `type=valeur` « Réactivité de l'analyse » avec puce **Bientôt** (désactivée). |
Règles : jamais d'interrupteur pour une capacité non annoncée (Kotlin sans Formatage ni Indices ;
XML sans Signatures — le renommage XML existe depuis codeide-lsp v1.1.0) ; chaque changement
est enregistré **immédiatement**
(`LspSettingsRepository`) et appliqué sans redémarrer quand il est côté client.

### L3 — Index et cache (`fragment_lsp_index.xml`)
| Carte | Contenu |
|---|---|
| **Occupation** | `RangeeParametre(valeur)` « Cache d'index » : taille sur disque (`tailleCacheOctets()`), « Dernier passage : 1 m 12 s, 3 avertissements ». |
| **Budget** | Rangée `valeur` « Taille maximale » : Auto · 256 Mo · 512 Mo · 1 Go. Info : « Auto s'adapte à la taille du projet ». Appliqué dynamiquement (`appliquer`). |
| **Détection des changements** | Interrupteur « Surveiller les modifications externes » + rangée `valeur` « Fréquence » : 2 s · 5 s (défaut) · 10 s · 30 s. Texte : « Utile après un git pull ou une édition depuis un autre outil. » |
| **Actions** | `Widget.CodeIDE.Button.Outlined` « Réindexer » ; `Widget.CodeIDE.Button.Outlined` « Réessayer les échecs » (visible seulement s'il y en a) ; **`Widget.CodeIDE.Button.Tonal`** destructif-doux « Vider le cache » → dialogue `MaterialAlertDialogBuilder` de confirmation (« L'index sera reconstruit à la prochaine ouverture. ») ; désactivé pendant une indexation, avec explication. Résultat via `Retour.snackbar` (+ « Annuler » inutile : l'action est reconstructible). |
Lien : « Détails de l'indexation » → ouvre le bottom sheet existant (ADR 0117) **si un projet est ouvert**, sinon désactivé.

### L4 — Environnement d'analyse (`fragment_lsp_environnement.xml`)
Lecture seule ; chaque valeur vient de l'hôte/de l'environnement, **aucune saisie de chemin**.
| Rangée | Valeur | Si absent |
|---|---|---|
| JDK | version (ex. « 17 ») · « Fourni par l'environnement » | `VueEtat(erreur)` « JDK introuvable » + bouton « Réparer » → `SectionParametres.ENVIRONNEMENT` |
| Plateforme Android | niveau d'API de l'`android.jar` | idem, « Plateforme non installée » |
| Mode du moteur Java | « Par module (Gradle) » ou « Instantané » selon ce que l'hôte annonce | texte explicatif |
Aucun chemin absolu affiché par défaut (règle 11) ; « Copier le chemin » **n'existe pas** (voir L5).

### L5 — État et diagnostic (`fragment_lsp_etat.xml`)
| Carte | Contenu |
|---|---|
| **Résumé** | Par moteur : état, dernière activité, nombre d'avertissements/erreurs du modèle d'indexation. |
| **Dernières erreurs** | Liste (≤ 20) `ligne_lsp_erreur` : heure · moteur · message court ; expurgée (`LogRedactor`). Vide → `VueEtat(vide)` « Aucune erreur ». |
| **Action** | `Widget.CodeIDE.Button.Outlined` « Copier le diagnostic LSP » → texte expurgé dans le presse-papiers + `Retour.snackbar` (jamais de contenu de fichier ; modèle : « Copier le diagnostic » de l'Environnement). |

## 4. Ressources à créer

- **Layouts** (`feature/settings/src/main/res/layout/`) : `fragment_lsp_apercu.xml`,
  `fragment_lsp_langage.xml`, `fragment_lsp_index.xml`, `fragment_lsp_environnement.xml`,
  `fragment_lsp_etat.xml`, `ligne_lsp_erreur.xml`. **Aucune** rangée recopiée : tout passe par
  `RangeeParametre`.
- **Chaînes** `lsp_*` FR (`values/`) **et** EN (`values-en/`), pluriels inclus ; libellés des
  fonctions pilotés par une **énumération** (jamais par comparaison de chaînes, principe ADR 0117).
- **Code** : `LangagesFragment` + `LangagesViewModel` (`UiState`/`Action`/`Effect`), présentateur
  pur `PresentationLsp` (testé en JVM, sans Android) transformant l'état hôte en modèle d'écran.
- **Aucune** nouvelle couleur, dimension ou style : si un manque apparaît, **lot `core:ui` d'abord**.

## 5. Accessibilité et robustesse

- Zones tactiles ≥ 48 dp (portées par `RangeeParametre`) ; chaque `IndicateurEtat` a un
  `contentDescription` complet (« Java : prêt ») ; changements d'état annoncés (`liveRegion polite`).
- Rotation/recréation : l'état affiché est ré-observé ; aucune action destructive ne se répète.
- Contrastes vérifiés en clair, sombre, dynamique ; police ×1,3 sans troncature des sous-titres
  (retour à la ligne autorisé).
- Tests : présentateur `PresentationLsp` (tous états × 3 langages), test d'architecture « liste
  d'interrupteurs = capacités annoncées ∩ consommables », test de non-régression visuelle manuelle
  consigné dans `docs/TESTS_MANUELS.md`.
