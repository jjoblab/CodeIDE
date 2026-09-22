# feature/newproject

Wizard de création en cinq étapes numérotées (Modèle, Configuration,
Informations et emplacement, Fichiers, Récapitulatif) puis écran de
création avec progression et rollback. Machine à états `WizardViewModel`
partagé scopé à l'hôte, rendu dynamique des paramètres depuis le moteur
de templates, champs dérivés qui suivent leurs sources tant que non
modifiés à la main.

Étape 10 (v0.11.0) : cadre complet (ADR 0020) — hôte, indicateur
d'étapes, barre d'actions, machine à états `SavedStateHandle` — et
étapes 1 à 3 (Modèle, Configuration, Informations et emplacement —
rendu dynamique ADR 0021, carte d'emplacement éphémère ADR 0022).

Étape 11 (v0.12.0, à venir) : étapes Fichiers et Récapitulatif,
écran de création avec progression et rollback.

Contenu fonctionnel détaillé : voir `README.md` du module.
