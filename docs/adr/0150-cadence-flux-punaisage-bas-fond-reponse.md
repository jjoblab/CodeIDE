# ADR 0150 — La cadence du flux, le punaisage au bas et le fond de la réponse (retour propriétaire : ANR, tremblements, différenciation)

- **Statut** : **accepté** (livré avec CodeIDE v0.196.0).
- **Date** : 2026-10-26.
- **Décideurs** : jjoblab (retour propriétaire post-v0.195.0 :
  « Il y a des ANR, ça bugue, fige avant de s'afficher les résultats.
  Ce n'est du tout fluide, l'écran tremble, saccade en affichant les
  résultats. Il devrait y avoir quelque chose qui différencie la
  réponse donnée, soit un fond ou background. Consultez les
  applications d'IA de chat pour voir comment ils ont fait. S'il faut
  créer un éditeur spécialement pour ça et ne pas utiliser seulement
  le RecyclerView, alors il faut le faire. »).
- **Dépend de** : ADR 0123 (contrat des fournisseurs IA), ADR 0127
  (jetons UI), v0.195.0 (rendu incrémental `RenduBlocsMarkdown`,
  raisonnement repliable).

## Contexte

Le retour propriétaire de la v0.195.0 décrit TROIS symptômes distincts
sur le chat en streaming, plus une demande de design. L'audit du
pipeline complet (SSE → ViewModel → Fragment → adaptateur) a isolé
QUATRE causes racines, toutes structurellement indépendantes :

1. **ANR + gel avant l'affichage** : la chaîne de rendu était déclenchée
   PAR JETON (20–60 deltas/seconde chez certains fournisseurs). Chaque
   delta portait : copie de la liste de messages, diff complet,
   DESTRUCTION + regonflement de la rangée de puces
   (`removeAllViews` + une inflation par puce, pour un résultat
   STRICTEMENT identique), re-parse Markdown + re-fabrication des spans
   de TOUS les blocs du message. Le fil principal saturait : les
   événements d'entrée s'empilaient → ANR.
2. **Tremblements / saccades** : le suivi bas était un
   `scrollToPosition(dernier)` — un SAUT franc — rejoué à CHAQUE
   delta, sur des hauteurs d'items qui changeaient en même temps.
3. **Gel « avant les résultats »** : la condition de suivi
   (`findLastCompletelyVisibleItemPosition >= lastIndex − 1`) devenait
   structurellement FAUSSE dès que le message en génération devenait
   plus HAUT que l'écran (réponse longue, gros bloc de code) :
   l'avant-dernier item ne pouvait plus être « complètement visible »,
   le suivi mourait en pleine réponse — l'écran figeait sur une
   réponse qui continuait de s'écrire hors champs.
4. **Aucune différenciation visuelle** de la réponse assistant : du
   texte posé directement sur le fond de l'écran (les clients de chat
   adultes — ChatGPT, Claude — posent la réponse sur une carte
   distincte, la question de l'utilisateur en bulle colorée).

## Décision

### 1. La cadence du flux borne le rendu, jamais le contenu

`ChatViewModel.cadencer()` : chaque fragment de texte/raisonnement
APPLIQUÉ est suivi d'une sieste bornée (`delay(50 ms)` — au plus 20
rendus/seconde). L'état ne peut plus être émis plus vite que le rendu
n'encaisse, QUEL QUE SOIT le débit des jetons. Les fragments arrivés
pendant la sieste s'accumulent dans le canal SSE (capacité 64 — le
lecteur réseau est mis en attente par contre-pression : AUCUN jeton
perdu) et le rendu suivant montre le texte CONFLATÉ — le rythme « le
texte coule par mots » des clients de chat. `Done`/`Failure` ne
dorment JAMAIS : la clôture est appliquée immédiatement, latence de fin
nulle. La sieste est annulable : « Arrêter » l'interrompt aussitôt. Sur
un flux au compte-gouttes, elle est gratuite (le collecteur attendait
le canal de toute façon).

### 2. Le punaisage PIXEL remplace le saut par position

`PunaisageFluxChat` (objet pur, testé en JVM) fournit les deux mesures :
`estPunaise(basDernierEnfant, hauteurZone, paddingBas)` — vrai tant
que le bas du dernier enfant posé reste à ≤ 8 px du bas visible ;
`surplomb(...)` — les pixels EXACTS à faire défiler pour recoller.
Tant que la lecture est punaisée, chaque application du diff est
suivie d'un `scrollBy(surplomb)` DANS LE RAPPEL DE COMMIT de
`submitList` (positions et hauteurs RÉELLES) : la réponse qui grandit
reste COLLÉE au bas, sans saut — les pixels suivent les pixels. Une
remontée de lecture détache le punaisage : plus AUCUN « yank » au
milieu d'une relecture ; un bouton flottant « Revenir en bas »
(`bouton_revenir_bas`, FAB chevron bas DANS `zone_messages`, le geste
standard des clients de chat) recolle au bas et reprend le suivi.
`scrollToPosition` ne survit que pour le saut FRANC d'un dernier item
HORS zone (conversation rouverte, premier message) — suivi d'un
recollage pixel après le passage de disposition, pour que même un
dernier message plus haut que l'écran atterrisse le BAS à l'écran.
« Envoyer » rearme le punaisage : l'attention revient à la
conversation.

### 3. La rangée de puces ne se regonfle que si son EMPREINTE change

`empreintePucesDe(etat)` (pur, testé en JVM) : types ET chemins des
pièces + budget de jetons — TOUT ce que la rangée affiche. La rangée
n'est détruite et regonflée QUE si l'empreinte change : les ~20
rendus/seconde du flux passent au travers sans la toucher (avant,
CHACUN détruisait et regonflait toutes les puces).

### 4. La garde anti-rendu des blocs Markdown inchangés

`RenduBlocsMarkdown` (l'« éditeur spécifique » du message en
génération, déjà en place en v0.195.0) gagne la garde : le texte BRUT
de chaque vue est comparé au texte brut du bloc — un bloc INCHANGÉ ne
re-passe NI par `setText` NI par la fabrication des spans (un `setText`
identique invalide quand même mesure/disposition). Pendant le flux,
seul le DERNIER bloc grandit : le coût de chaque image est borné au
rendu du bloc qui coule. Le RecyclerView reste l'outil des messages
(finalisés) — le message en génération, lui, vit dans son éditeur
dédié : c'est la séparation demandée par le propriétaire, sans jeter
le bon outil pour l'historique.

### 5. La réponse assistant porte un fond, les blocs de code contrastent

`fond_reponse_assistant` (coins `corner_radius_large`, teinte
`?attr/colorSurfaceVariant`) : la réponse se lit comme une ZONE
distincte, la bulle utilisateur (colorPrimaryContainer) gardant son
rôle. Les blocs de code passent de `colorSurfaceVariant` à
`?attr/colorSurface` : un bloc NEUTRE sur une carte teintée, lisible
dans les deux thèmes — inverse de l'avant, où code et message se
fondaient l'un dans l'autre.

## Conséquences

- Le fil principal ne voit plus que ≤ 20 rendus/seconde pendant le
  flux, chacun borné (blocs inchangés court-circuités, puces
  intactes, diff sur l'identifiant) : les ANR de saturation
  disparaissent, le « fige avant les résultats » aussi (mesure pixel
  insensible à la hauteur du message en génération).
- Le débit de CONSOMMATION SSE est borné par la cadence — la
  contre-pression tamponne dans le canal du `SseStreamExecutor`, puis
  dans la socket : aucun jeton perdu, la clôture reste immédiate.
- Tests : `PunaisageFluxChatTest` (7, maths pures), `EmpreintePucesDeTest`
  (4, empreinte), `ChatViewModelTest` +1 (rafale de 24 fragments :
  premier fragment visible sans attendre, contenu intégral au bout,
  clôture immédiate), `LayoutsIaTest` étendu (FAB dans la zone,
  fond de la réponse, bulle conservée).
- Recette manuelle : `docs/IA_TESTS_MANUELS.md` T13 (fluidité du flux
  sur réponse longue avec blocs de code — punaisage, détachement,
  bouton « Revenir en bas »).
