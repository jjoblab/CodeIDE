# feature/onboarding — Assistant de premier lancement

> Statut étape 5 (v0.6.0) : complet et testé.

Assistant de premier lancement en cinq pages — bienvenue, dossier de
travail (SAF + test d'écriture), apparence (aperçu immédiat), profil,
terminé — `ViewPager2` **non swipable**, indicateur de progression,
transitions `MaterialSharedAxis`. L'état survit à la rotation et à la
mort du processus.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model` (jamais les autres
fonctionnalités).

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la
tâche `./gradlew checkModuleDependencies` qui fait échouer le build en cas
de violation.

## API publique

- `OnboardingFragment` — hôte du pager, destination `R.id.onboarding`
  du graphe de navigation ; `MainActivity` y route quand
  `isSetupCompleted` est faux (premier lancement).
- `OnboardingViewModel` — UDF (section 5.3) : `onAction(ActionOnboarding)`
  en entrée, `etat: StateFlow<EtatOnboarding>` et
  `effets: Flow<EffetOnboarding>` en sortie.
- Pages : `BienvenuePage`, `DossierPage`, `ApparencePage`, `ProfilPage`,
  `TerminePage` (fragments enfants partageant le ViewModel du parent).

## Comportements clés

- **Test d'écriture** : un dossier sélectionné n'est validé qu'après
  création + écriture + suppression d'un fichier témoin ; tout échec
  relâche la permission persistante prise (plafond système de 512).
- **Dossiers refusés** par Android 11+ (racine, `Download`,
  `Android/data`, `Android/obb`) : message clair par raison, aucune
  permission prise.
- **Étape passable** : « Plus tard » mène au bandeau
  « Configurer le dossier de travail » de l'accueil.
- **Aperçu immédiat** : chaque choix d'apparence se persiste à
  l'instant ; `MainActivity` recrée l'écran (thème, couleurs
  dynamiques, langue via `AppCompatDelegate.setApplicationLocales`,
  ADR 0013).
- **Survie** : page et champs profil dans le `SavedStateHandle` ;
  l'amorçage depuis les paramètres réels n'a lieu qu'une fois par vie
  du sauvetage.

## Vérifications du module

```bash
./gradlew :feature:onboarding:check
```

Procédures manuelles sur appareil : O1-O6 dans `docs/TESTS_MANUELS.md`.
