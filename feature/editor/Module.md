# feature/editor

Espace de travail de l'éditeur, implémenté à l'étape 13 (v0.14.0, ADR 0026 — fondations sans logique) : `EditorActivity` à trois zones (tiroir — permanent sur grand écran, zone centrale à états vides, panneau inférieur replié Console · Problèmes · Journal), navigation `AppNavigator.openEditor` par-dessus la pile depuis l'accueil et le succès du wizard, `EditorViewModel` suivant le projet au registre via `SavedStateHandle`. Voir `README.md` du module.
