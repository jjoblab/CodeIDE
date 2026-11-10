# feature/newproject

Wizard de création à **parcours adaptatif** : le domaine calcule la liste
des étapes réelles (`CalculeParcoursUseCase`, ADR 0155) à partir du bloc
`wizard` déclaré par le manifeste du modèle — socle imposé (Modèle →
étapes du modèle → Identité → Fichiers → Récapitulatif), étapes
conditionnées par `quand`, préréglages posés d'un geste, mode rapide
(préréglage puis récapitulatif). Machine à états `WizardViewModel`
partagé scopé à l'hôte, rendu dynamique des paramètres depuis le moteur
de templates, champs dérivés qui suivent leurs sources tant que non
modifiés à la main, aperçu du plan **en direct** (antirebond 300 ms,
différentiel ajouté/retiré, feuille basse téléphone ou panneau latéral
tablette).

Le rendu des paramètres suit le contrat d'affichage déclaratif (ADR
0154) : composant indicé par le manifeste (`rendu` → `RenduParametre`),
libellés/sous-titres/icônes des choix résolus par le moteur
(`ChoixEvalue` via clés i18n `param.<id>.choix.<valeur>.*`), titres de
groupe et zone « Avancé » déclarés — aucun registre de présentation
côté UI (amende de l'ADR 0021). Les contraintes de combinaison
(`enabledWhen`, règles ERREUR/AVERTISSEMENT/INFO, `autoDefinit`) sont
évaluées par le domaine (ADR 0153) et affichées avec raison (étape
désactivée, avertissement, blocage de création) ; revalidation finale
dans `CreateProjectUseCase`.

Étape 10 (v0.11.0) : cadre complet (ADR 0020) — hôte, indicateur
d'étapes, barre d'actions, machine à états `SavedStateHandle` — et
étapes 1 à 3 (Modèle, Configuration, Informations et emplacement —
rendu dynamique ADR 0021, carte d'emplacement éphémère ADR 0022).

Étape 11 (v0.12.0) : étapes 4 Fichiers (options communes du moteur :
README/.gitignore/.editorconfig, licence pré-remplie auteur+année,
langue du contenu FR/EN) et 5 Récapitulatif (« Modifier » par section,
arborescence prévue repliable), écran de création piloté par `EtatCreation`
(ADR 0023 : progression temps réel, annulation = rollback, succès/échec
typés), bouton « Créer le projet », mise en évidence à l'accueil
(ADR 0024).

Refonte « générateur et écrans Nouveau projet » (lots L1 à L5, v0.209.0
→ v0.213.0, ADR 0153-0155/0158) : socle moteur des règles déclaratives,
parcours adaptatif consommé par l'état et les écrans (registre de
fragments à la place du `when` exhaustif), étapes communes fidèles
(Identité, Fichiers par groupes du modèle, Récapitulatif organisé selon
le parcours réellement suivi avec « Modifier » par étape), rendu
enrichi, aperçu en direct et mode rapide. Lot L6 (v0.214.0) : dette
`checkUiTokens` du module soldée (aucune occurrence — layouts passés
aux styles maison `Widget.CodeIDE.*` et au jeton
`@dimen/elevation_aucune`), entrées sorties de la baseline du cliquet.

Contenu fonctionnel détaillé : voir `README.md` du module.
