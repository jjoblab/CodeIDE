# feature:install

Écran d'installation du bootstrap natif — prompt compagnon « Terminal
intégré et bootstrap natif » (Terminal-1), sections 3.4 et 6.

## Périmètre livré (étape T3, v0.22.0)

- `InstallViewModel` : traduction **pure** de l'état partagé
  `BootstrapInstaller.etat` (port `core:domain`, pipeline et singleton
  dans `core:bootstrap`, ADR 0033) vers un état de rendu — phase
  (invite/progression/terminée/échec/annulée), libellé d'étape,
  progression bornée du téléchargement, erreur typée, état par outil.
  Les ordres `Installer`/`Annuler` sont relayés au port ; `Fermer` est
  de la navigation pure.
- `InstallFragment` : une phase visible à la fois, messages d'erreur
  **actionnables** (les détails techniques partent au journal via
  l'installateur), annulation explicite, retour système = fermeture
  sans interruption.
- Le fragment ne possède **rien** : rouvert depuis l'autre point
  d'entrée pendant une installation, il affiche la progression réelle.

## Dépendances

`core:ui`, `core:domain`, `core:model` — règles de la section 5.2 ;
aucune bibliothèque Termux ici (réservées à `core:terminal-runtime` et
`feature:terminal`).

## Tests

`InstallViewModelTest` (fake `core:testing`) : traduction de chaque
état partagé, progression bornée/indéterminée, temps réel, relais des
ordres.
