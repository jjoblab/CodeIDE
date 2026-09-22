# feature/newproject — Fonctionnalité — wizard de création de projet

> Statut étape 11 (v0.12.0) : wizard **complet** — cinq étapes + écran de
> création (section 12 du prompt maître, ADR 0020-0024).

Wizard de création en cinq étapes numérotées (Modèle, Configuration,
Informations et emplacement, Fichiers, Récapitulatif) puis écran de
création avec progression et rollback. Machine à états `WizardViewModel`
partagé scopé à l'hôte, rendu dynamique des paramètres depuis le moteur
de templates, champs dérivés qui suivent leurs sources tant que non
modifiés à la main.

## Structure (étapes 10-11)

- **`NewProjectFragment`** (hôte, ADR 0020) : barre d'outils (✕ + dialogue
  d'abandon), indicateur « Étape N sur M », conteneur d'étapes, barre
  d'actions fixe Retour/Suivant, transitions `MaterialSharedAxis` axe X
  (coupées si animations réduites), retour système géré, tablette
  `layout-sw600dp` bornée.
- **`WizardViewModel`** : machine à états + état unique `EtatWizard`
  (rotation et mort du processus via `SavedStateHandle`), réévaluation du
  formulaire à chaque changement, vérification de cible asynchrone avec
  délai 400 ms, relâchement de l'emplacement éphémère à l'abandon.
- **`EtapeModeleFragment`** (étape 1) : grille de cartes sélectionnables,
  monogramme résolu depuis l'i18n, recherche masquée sous 4 modèles.
- **`EtapeConfigurationFragment`** (étape 2) : rendu dynamique
  (`RenduParametres`, ADR 0021) + puces récapitulatives en direct.
- **`EtapeInformationsFragment`** (étape 3) : nom, description,
  paramètres INFORMATION dérivés, carte d'emplacement (ADR 0022) avec
  vérifications asynchrones.
- **`EtapeFichiersFragment`** (étape 4) : interrupteurs des fichiers
  optionnels, licence pré-remplie des Paramètres (auteur + année),
  langue du contenu générée (boutons segmentés FR/EN) → `TemplateOptions`.
- **`EtapeRecapitulatifFragment`** (étape 5) : résumé par section avec
  boutons « Modifier » (retour arrière direct), **arborescence prévue
  repliable** (`Arborescence` : transformation pure du plan dry-run).
- **`EcranCreationFragment`** + `EtatCreation` (ADR 0023) : progression
  temps réel du flot de `CreateProjectUseCase`, Annuler = rollback
  domaine, succès (ouvrir/accueil/autre projet), échec typé avec
  Réessayer et Copier les détails.
- **`ModelesAdapter`** : cartes du catalogue (DiffUtil, recherche sans
  casse ni accents).

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- **`NewProjectFragment`** — destination `newproject` du graphe de
  navigation (action `action_home_to_newproject`).
- Mise en évidence du projet créé à l'accueil (ADR 0024 : identifiant
  transmis via la pile de retour, consommé par `HomeFragment`).

## Vérifications du module

```bash
./gradlew :feature:newproject:check
```
