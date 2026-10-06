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

## Nouvel écran d'installation E5 (v0.59.0, ADR 0090)

`InstallationFragment` + `InstallationViewModel` projettent l'état du
parcours de la refonte (`EnvironmentSetupOrchestrator`, ADR 0085/0087) :
stepper vertical de 4 cartes (Bootstrap, Outils, Java, Android),
progression « étape N sur 4 », journal en direct repliable (monospace,
copiable), vitesse et temps restant **mesurés** du téléchargement courant
(jamais extrapolés), consentement licence avant la phase `ANDROID_SDK`
(§ 12.5), actions contextuelles masquées hors contexte (jamais grisées),
récapitulatif final des versions vérifiées + « Créer mon premier
projet », variante tablette sw600dp (stepper à gauche, journal à droite
toujours visible). Le ViewModel ne décide rien : l'installation vit dans
le service de premier plan, l'écran survit à la rotation et à la mort du
processus. L'ancien écran (`InstallFragment`, T3) reste compilable mais
inatteignable — sa suppression est planifiée en E6 (§ 9 du cahier des
charges).

Tests : `InstallationViewModelTest` (traduction pure — compteur,
sous-étape, vitesse/ETA sur échantillons, diagnostic, relais des ordres ;
fake `FakeEnvironmentSetupOrchestrator`).

## Dépendances

`core:ui`, `core:domain`, `core:model` — règles de la section 5.2 ;
aucune bibliothèque Termux ici (réservées à `core:terminal-runtime` et
`feature:terminal`).

## Tests

`InstallViewModelTest` (fake `core:testing`) : traduction de chaque
état partagé, progression bornée/indéterminée, temps réel, relais des
ordres.
