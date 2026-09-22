# feature/newproject

Wizard de création en cinq étapes numérotées (Modèle, Configuration, Informations et emplacement, Fichiers, Récapitulatif) puis écran de création avec progression et rollback. Machine à états `WizardViewModel` partagé scopé à l'hôte, rendu dynamique des paramètres depuis le moteur de templates, champs dérivés qui suivent leurs sources tant que non modifiés à la main.

Étape 7 (v0.8.0) : `NewProjectFragment` placeholder (destination du graphe,
retour accueil). Wizard complet aux étapes 10-11.

Contenu fonctionnel détaillé : voir `README.md` du module.
