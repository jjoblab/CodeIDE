# feature/editor — Espace de travail de l'éditeur (étapes 13-15)

`EditorActivity` — activité séparée de `MainActivity`, trois zones
(ADR 0026, 0027 et 0028) :

- **tiroir de navigation gauche** : en-tête (nom du projet, chemin
  lisible, bouton **Actualiser** — revérifie l'accès puis recharge,
  « Fermer le projet ») — **permanent verrouillé ouvert sur grand
  écran** (sw600dp+, façon IDE de bureau) ;
  - **explorateur de fichiers paresseux** (étape 14, ADR 0027) : un
    dossier n'énumère ses enfants (`FileSystem.list`) qu'à son premier
    dépliement, le résultat est mis en cache dans le `EditorViewModel` ;
    tri dossiers puis fichiers puis alphabétique ; icônes par extension
    (`IconesFichiers` de `core:ui`) ; bandeau d'accès
    (`ProjectAccessState` — permission perdue / introuvable / erreur)
    avec action « Résoudre à l'accueil » ; un dossier défaillant est
    signalé par sa ligne, l'appui réessaie ;
  - **barre de navigation basse** : Explorateur active, Recherche et
    Git visibles mais désactivées (« Bientôt disponible ») ;
- **zone centrale** — barre d'outils au nom du projet (action
  **Enregistrer**), **onglets de fichiers dynamiques** (étape 15, ADR
  0028 : icône du langage, point de modification remplaçant la
  fermeture tant que l'onglet est sale, menu contextuel — Fermer,
  Fermer les autres, Fermer tout, Déplacer à gauche/droite, Copier le
  chemin) et **un seul `EditorView` cel-ui** rebranché sur la session
  de l'onglet actif, thème clair/sombre suivant l'application. Les
  sessions (`EditorDocument`/`EditorSession`, classes pures de
  cel-core) vivent dans le `EditorViewModel` ; un fichier binaire est
  proposé à « Ouvrir avec » plutôt qu'affiché illisible. Sauvegarde
  automatique (1,5 s d'inactivité) et manuelle via
  `FileSystem.writeText`, verrou par fichier ; dialogue de fermeture
  avec modifications non enregistrées (agrégé) — l'auto-sauvegarde est
  suspendue sous confirmation ; `session.dispose()` à chaque fermeture
  et à la destruction ; onglets rouverts après mort du processus
  (`SavedStateHandle`) ;
- **panneau inférieur replié** : en-tête à poignée (replié ↔ mi-hauteur)
  et trois onglets vides Console · Problèmes · Journal (étape 16).

Le bouton retour ferme le tiroir s'il est ouvert, sinon demande la
sortie — avec confirmation agrégée si des onglets sont sales. Le
`EditorViewModel` charge le projet reçu par l'intention (identifiant
par `SavedStateHandle`) et le suit au registre : renommage,
relocalisation (réinitialise l'arborescence) ou suppression depuis
l'accueil se répercutent sans rechargement.

Le contenu du panneau inférieur (étape 16) et les actions du tiroir
(étape 17) s'ajouteront à ce cadre.

## Dépendances autorisées

`core:ui`, `core:domain`, `core:model` — **et**
`com.github.jjoblab.code-editor:cel-ui` (JitPack) : la seule dépendance
externe autorisée dans une fonctionnalité, exception documentée (ADR
0026, prompt compagnon section 3) — bibliothèque de composants d'UI au
même titre que Material Components, pas une source de données. Les
fichiers ne sont lus **que** via l'interface `FileSystem` du domaine,
jamais d'accès direct au SAF ni à `java.io.File`.

Règles complètes : `docs/ARCHITECTURE.md` § « Règles de dépendance » et la tâche
`./gradlew checkModuleDependencies` qui fait échouer le build en cas de violation.

## API publique

- `EditorActivity` — lancée par `AppNavigator.openEditor(projectId)`
  (extra `ClesEditor.EXTRA_PROJECT_ID`).
- `ActionEditor` — intentions du tiroir et des onglets : `Rafraichir`,
  `BasculerNoeud(uri)`, `OuvrirFichier(uri)`, `SelectionnerOnglet`,
  `FermerOnglet`/`FermerAutresOnglets`/`FermerTousOnglets`,
  `DeplacerOnglet`, `Enregistrer`, `EnregistrerPuisFermer`,
  `FermerSansEnregistrer`, `Quitter`.
- `EffetEditor` — événements ponctuels : `OuvrirAvec` (binaire),
  `ConfirmerFermeture` (dialogue), `CopierChemin`, `Quitter`, erreurs
  d'ouverture et d'enregistrement.

## Tests

`EditorViewModelTest` : chargement et suivi du registre, identifiant
inconnu, projet supprimé ; explorateur — tri, énumération paresseuse
et cache (compteur d'appels de `FakeFileSystem`), permission perdue
(bandeau), actualisation, dossier disparu (nœud en erreur réessayable),
relocalisation ; onglets — ouverture (session et langue réelles),
binaire détourné, modification et **auto-sauvegarde** (temps virtuel),
sauvegarde manuelle, confirmation de fermeture (simple et agrégée),
fermer les autres, déplacement, **mort du processus** (onglets
rouverts), échec d'enregistrement (onglet sale, signalé).
`SessionEditionTest` : intégration réelle avec cel-core (vrais
`EditorDocument`/`EditorSession`, sans vue) — critère d'acceptation.

```bash
./gradlew :feature:editor:check
```
