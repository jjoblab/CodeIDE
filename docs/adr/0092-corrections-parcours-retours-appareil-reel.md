# ADR 0092 — Corrections du parcours après retours d'appareil réel (v0.60.1)

- **Statut** : accepté (correctifs post-E6, premiers retours d'exécution du
  parcours complet sur appareil Android aarch64)
- **Contexte** : v0.60.0 fusionnée sur `main`, parcours complet
  `BOOTSTRAP` → `PACKAGE_TOOLS` → `JAVA` → `ANDROID_SDK` exécuté pour la
  première fois sur un appareil réel. Deux blocages constatés :

1. **Phase `JAVA` échouée au premier passage** alors que l'installation a
   **réussi** : le journal montre `Setting up openjdk-17 (17.0.20)` puis
   `Setting up openjdk-17-x` (paquets posés et configurés, 115 Mio
   téléchargés), suivis de `W: … EIPP::OrderInstall (2: No such file or
   directory)` et `E: Directory '…' missing` — et un **code de sortie 100**.
   `apt` du préfixe (Android/Termux-like) peut donc renvoyer un code non
   nul **après** une opération réussie. Le second lancement vérifiait la
   phase sans rien retélécharger (« déjà vérifiée — reprise ») : l'état
   réel de l'appareil prouve que seul le **verdict fondé sur le code de
   sortie** était faux.
2. **Phase `ANDROID_SDK` jamais démarrée** : « licence du SDK Android non
   acceptée — parcours suspendu (§ 12.5) » répété à chaque tentative. Le
   bouton « Installer le SDK » de l'écran d'installation appelait
   `demarrer()` seul : **l'acceptation n'était jamais enregistrée**
   (`InstallationViewModel.accepterLicence()` existait mais n'était appelé
   par personne — la case à cocher ne faisait qu'activer le bouton).

## Décision

### 1. La sortie de `apt`/`pkg` n'est pas un verdict — l'exécution réelle tranche

Le principe « installé = vérifié en l'exécutant » (ADR 0085, § 3.2 du
cahier) est étendu au verdict des **commandes d'installation elles-mêmes** :
après un code de sortie non nul de `pkg install`, la vérification par
exécution est jouée **avant tout échec** —

- l'outil répond (JDK : `java`/`javac -version` + majeure du catalogue ;
  paquet de la phase 2 : commande de vérification § 5.2) → l'installation
  est **réussie malgré le code** : journal explicite (« anomalie apt
  connue : sortie non fiable — poursuite »), le parcours continue ;
- l'outil ne répond pas → échec `Commande` **inchangé**, avec la sortie
  apt à l'appui (le comportement précédent est couvert par les tests
  existants : « l'échec d'installation du paquet échoue la phase en
  Commande »).

Trois sites, le même geste :

- `PhaseJava.EtapePaquetJdk` (phase 3) — le constat appareil exact ;
- `PhaseOutilsPaquets.EtapePaquet` (phase 2) — même défaut latent :
  `curl`, `ca-certificates`, `tar`, `xz-utils`, `unzip` auraient pu
  échouer de la même façon (la phase était « vérifiée » sur l'appareil de
  test uniquement parce que les outils venaient du bootstrap) ;
- `PhaseOutilsPaquets.EtapeMiseAJourPaquets` (phase 2) — après l'échec
  des 4 stratégies `pkg update`/`apt update`, une **sonde locale**
  `apt-cache policy <paquet JDK du catalogue>` tranche : un candidat
  visible (≠ `(none)`) prouve que les listes répondent — la mise à jour a
  produit son effet malgré les codes. La sonde ne fait pas de réseau : si
  les listes sont réellement absentes, l'échec `Reseau` est rendu comme
  avant (couvert par le test existant « l'échec persistant échoue la
  phase en Reseau »).

Le seuil de tolérance est volontairement **limité aux commandes
d'installation/mise à jour de paquets** : les téléchargements (SHA-256 du
manifeste), l'extraction, les contrôles par exécution et la phase 4
conservent leur verdict strict — l'anomalie constatée est spécifique à la
chaîne `apt`/`pkg` du préfixe.

### 2. Consentement licence § 12.5 — enregistré AVANT le lancement, de façon séquentielle

- Le port `EnvironmentSetupOrchestrator.acceptSdkLicense()` devient
  **`suspend`** : l'acceptation est **persistée avant le retour** — un
  `run()` appelé juste après est garanti de la voir. L'implémentation
  précédente lançait une coroutine détachée : même appelée, elle autorisait
  une course avec le parcours (la garniture de test devait poller l'état
  jusqu'à 5 s pour la voir — le symptôme de la course était déjà là).
- L'écran d'installation câble le bouton principal : case cochée + clic
  « Installer le SDK » → `accepterEtDemarrer()` — consentement puis
  `run()` **dans cet ordre, en une seule coroutine**. La case seule
  n'enregistre rien : le geste complet (cocher PUIS agir) est l'acte
  d'utilisateur exigé par § 12.5.
- **Reprise après mort du processus** (trou voisin révélé par l'analyse) :
  licence acceptée + phase `ANDROID_SDK` jamais exécutée + parcours
  immobile → le bouton principal réapparaît « Installer le SDK » activé
  d'office (plus de case : le consentement est déjà enregistré) ; mort du
  processus **entre deux phases avant `JAVA`** → « Reprendre
  l'installation » (nouvelle chaîne fr/en). Auparavant ces deux états
  n'offraient aucune action sur l'écran (seul l'écran Environnement
  permettait de réparer) ; un échec reste couvert par « Réessayer cette
  phase », jamais deux actions concurrentes.

## Conséquences

- Le parcours ne peut plus être bloqué par un code de sortie apt mensonger
  : seul l'état réel de l'outil compte (philosophie ADR 0084/0085
  réappliquée aux commandes elles-mêmes).
- `acceptSdkLicense()` étant `suspend`, tout futur appelant enregistre le
  consentement de façon durable avant d'agir — l'API interdit la course.
- Tests : 5 nouveaux (code 100 + JDK posé poursuit ; code 100 + paquet
  posé poursuit ; mise à jour en échec apparent + listes fonctionnelles
  poursuit ; acceptation visible dès le retour ; consentement + lancement
  relayés ensemble), 3 simplifiés (plus de poll d'état).
- Non vérifié appareil (règle 2 du prompt) : la reproduction exacte de
  l'anomalie apt (EIPP/« Directory missing ») n'est pas possible hors
  préfixe Android — les tests rejouent le **comportement observé** (code
  100 + paquets posés), pas la cause interne d'apt. Scénarios manuels
  E96-E98 consignés dans TESTS_MANUELS.md.
