# app

Point d'entrée de CodeIDE : héberge `MainActivity` (SplashScreen, NavHost,
edge-to-edge), `CodeIdeApplication` (Hilt, StrictMode/LeakCanary en debug), le
graphe de navigation et l'implémentation de `AppNavigator`. C'est le seul module
autorisé à dépendre de tout le reste ; il ne contient aucune logique métier.

Contenu fonctionnel détaillé : voir `README.md` du module.
