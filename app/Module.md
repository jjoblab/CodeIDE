# app

Point d'entrée de CodeIDE : héberge `MainActivity` (SplashScreen, NavHost,
edge-to-edge), `CodeIdeApplication` (Hilt, StrictMode/LeakCanary en debug), le
graphe de navigation et l'implémentation de `AppNavigator`. C'est le seul module
autorisé à dépendre de tout le reste ; il ne contient aucune logique métier.

Contenu fonctionnel détaillé : voir `README.md` du module.

Statut : étapes 0 à 8 livrées (v0.9.0) — assemblage final, journalisation, plantages, couche données, navigation et **câblage du moteur de templates** (`AssetTemplateAssetsSource` sur l'AssetManager, `GeneratorVersionImpl` depuis `BuildConfig`, multibinding `@IntoSet`, licences SPDX officielles dans `assets/licenses/`).
