# ADR 0140 — La preview Compose C1 vit dans la carte « goutte d'eau » de l'éditeur

- **Statut** : **accepté** (livré avec CodeIDE v0.174.0).
- **Date** : 2026-10-24
- **Décideurs** : jjoblab (instruction propriétaire : « Pour la preview
  compose, je veux utiliser la goutte d'eau de code-editor pour accueillir
  la preview, faire le mise a jour nécessaire dans code-editor si
  nécessaire. GO C1 ») ; cadrage antérieur PC1–PC4 (PREVIEW.md §4.3,
  étape C1 §4.4) et étude PE14 §5 (audit AS, phases C0–C3 §5.4).
- **Dépend de** : cel-ui B27 (carte d'aperçu « goutte d'eau » — contrat
  `EditorPreviewHost`, AUCUNE mise à jour de la bibliothèque n'était
  nécessaire), codeide-lsp 1.4.0 (`lsp-kotlin` : `PreviewKotlin`, arbre
  d'appels neutre — frontière D6 du frère), ADR 0002 (pas de Compose pour
  l'UI CodeIDE), socle `:preview` de PE2 (processus dédié, AIDL, parcelables
  opaques).

## Contexte

PREVIEW.md §4 cadrerait la preview Compose depuis l'origine : **voie
interpréteur** sur l'arbre résolu du LSP Kotlin (PC1), **module séparé**
(PC2), statique d'abord / interactif ensuite (PC3), garde-fous
obligatoires (PC4) — séquencée en C0 (spike), C1 (statique persistant),
C2 (ressources/thème), C3 (interactif). L'étude PE14 §5 a audité la
manière dont Android Studio rend Compose (son spike C0 a été livré par
S9/v0.173.0 DANS le frère LayoutLib — composition dans la session
existante, mesures de latence ; ce C1 en est la suite côté CodeIDE) et **confirmé le cadrage** :
le live-edit est un diff d'identité avec état préservé, l'interactif
vit dans le runtime, les erreurs veulent un catalogue TYPIQUE dès le
départ, et l'hôte du rendu est une vue Android réelle. Le propriétaire a
ensuite arbitré l'ACCUEIL : la carte « goutte d'eau » de l'éditeur
(cel-ui B27 — le popup ancré à l'œil de gouttière) hébergera la preview
Compose, l'écran dédié `:feature:preview` restant la propriété du XML.

La découverte est déjà en place côté LSP : codeide-lsp 1.4.0 expose
`SessionLspKotlin.previews(uri)` (`PreviewKotlin` = FQN + nom + paramètres
de l'annotation + arbre d'appels NEUTRE — l'appelant interprète, le
frère ne sait pas ce qu'est Compose).

## Décisions

- **D1 — L'accueil est la carte « goutte d'eau », hôte `EditorPreviewHost`
  dédié au Kotlin** : `HostApercuCompose` (feature:editor) implémente le
  contrat cel-ui B27 ; `canPreview` répond pour `.kt`/`.kts` SEUL — l'œil
  n'apparaît JAMAIS sur un layout XML (garde de régression testée),
  l'aperçu XML garde son écran dédié sans aucun changement de
  comportement. `prefersDropPreview` vrai : le tap ouvre TOUJOURS la
  carte, jamais la feuille/le panneau scindé. La bibliothèque code-editor
  n'a exigé AUCUNE mise à jour — le contrat B27 (onglets, badge, barre de
  taille, écran du mini-téléphone) couvrait déjà tout ; les seules
  évolutions nécessaires vivaient côté CodeIDE.
- **D2 — Les ONGLETS de la carte sont les `@Preview` découvertes** : une
  pastille par fonction, dans l'ordre du document ; l'indice de
  `EtatPreviewCompose.RenduValide.rendus` EST l'onglet. Le contrat cel-ui
  « libellés mesurés à l'ouverture, stables tant que la carte vit » est
  honoré honnêtement : une liste qui CHANGE la carte vivante la referme/
  ré-ouvre au même caret (glissade 180 ms de la bibliothèque, l'onglet
  retombe à 0 — le coût ne porte que sur l'ajout/retrait d'une `@Preview`
  en cours d'édition).
- **D3 — Moteur `MoteurPreviewCompose`, miroir du frère XML avec DEUX
  divergences assumées** : (a) la DÉCOUVERTE EST INJECTÉE — le moteur ne
  connaît pas le LSP, chaque branchement porte un FOURNISSEUR paresseux
  invoqué APRÈS le debounce (400 ms) sur le fil du moteur : une frappe ne
  coûte AUCUN parse, jamais de session morte capturée ; (b) le rendu est
  TOUJOURS une SESSION PERSISTANTE du processus `:preview` — le programme
  converti est POUSSÉ dans la composition vivante de l'identité
  (`uri#nom#index`), c'est la RECOMPOSITION Compose qui diff, les
  `remember` survivent aux éditions (live-edit par diff d'identité, PC1).
  Anti-boucle par DIGESTE : chaque programme porte son SHA-256 ; digeste
  et paramètres inchangés → zéro appel Binder, zéro recomposition.
- **D4 — Interprétation par le REGISTRE, erreurs typées en pastilles
  isolées** : `ConvertisseurProgramme` convertit l'arbre neutre en
  `ProgrammeCompose` (genres Texte/Colonne/Rangée/Bouton/Espacement…,
  littéraux et arguments nommés interprétables) ; `RenduCompose` rend
  chaque genre par son composable RÉEL de la bibliothèque embarquée
  (runtime Compose confiné à `:preview:compose`, ADR 0002 intact — BOM
  2026.09.00). Un nœud hors registre, une expression non interprétée ou
  un composable de projet à paramètres devient une PASTILLE d'erreur
  TYPISEE (`GenreErreurCompose` — miroir `createMockView` de la
  référence : le nœud fautif est isolé, LE RESTE SE REND, jamais de
  rendu tout-ou-rien) ; le budget d'interprétation coupe honnêtement avec
  sa pastille dédiée.
- **D5 — Garde-fous PC4 livrés avec C1** : composition et recomposer
  JAMAIS dans le processus de l'IDE (`ServicePreviewCompose` bound-only
  dans le MÊME processus `:preview` que le service XML — une machinerie,
  deux dialectes) ; sandbox par construction (le programme n'exécute QUE
  les composables du registre — réseau, stockage et réflexion
  IMPOSSIBLES) ; `LocalInspectionMode` vrai ; budgets bornés (rendu chaud
  9 s, premier rendu 40 s, recomposition 5 s — sous les budgets transport
  45/15/10 s du frère) ; un rendu enlisé laisse un zombie qui OCCUPE sa
  session (verrou tryLock : le rendu suivant répond l'échec honnête,
  jamais un rendu fantôme) ; la mort du processus `:preview` donne UNE
  reprise puis l'état TYPIQUE « indisponible » (`IsolationCompose`) —
  AUCUN repli local (composer du code utilisateur dans l'IDE est
  exactement ce que PC4 interdit). Le watchdog de gel avec kill reste le
  chantier C3, documenté.
- **D6 — Câblage éditeur : l'hôte vit dans `EditorViewModel`** : il
  SURVIT aux re-créations de l'activité, l'activité ne fait que POSED la
  vue (`installerSur` — la vue est un support, pas le propriétaire) ;
  l'onglet actif suit l'état de l'espace (chaque changement re-branche
  le moteur), les frappes passent par `onPreviewContentChanged` →
  `soumettre()` (le moteur débounce et diff), la ré-ouverture de la
  carte est une NOUVELLE TENTATIVE du processus (« Réessayer » miroir).
  Le contexte d'application injecté par Hilt est NULLABLE À DÉFAUT NULL :
  les suites JVM pures du ViewModel ne construisent jamais l'hôte
  (l'œil n'apparaît pas), la non-nullité de fait en production est
  verrouillée par le test d'installation de l'activité.
- **D7 — Contrat « jamais de carte vide »** : pendant les frappes et les
  signaux discrets (`EnEdition`, `Inactif`), le DERNIER rendu valide de
  chaque onglet RESTE affiché ; les messages honnêtes distinguent la
  RECHERCHE (aucune passe n'a répondu), l'ABSENCE (une passe a trouvé
  rien), l'échec du DOCUMENT et l'échec LOCAL d'un onglet.

## Options rejetées

- **L'écran dédié `:feature:preview` comme hôte** : refusé par
  l'instruction propriétaire (la goutte d'eau accueille la preview) ;
  l'écran dédié reste spécialisé XML (planche, zoom, arbre, attributs —
  outillage sans objet pour une composition interprétée C1).
- **La voie compilateur par frappe** : rejetée dès PC1 (coût D8 par
  édition, secondes) — l'interpréteur donne la latence interactive.
- **Le rendu dans le processus de l'IDE avec repli** : refusé (PC4) —
  c'est l'unique raison d'être du processus `:preview`.
- **Un sélecteur de profil/jour-nuit dans la carte dès C1** : reporté —
  le profil (Pixel 4 miroir du frère) et le régime nuit sont des
  PARAMÈTRES du moteur, un chantier ultérieur les exposera à la carte.

## Conséquences

- **Nouveau module `:preview:compose`** (PC2) : dépend UNIQUEMENT de
  `:core:domain` (audit ProjectDependency), runtime Compose EMBARQUÉ
  comme bibliothèque d'interprétation (BOM 2026.09.00, plugin compose
  livré avec Kotlin), `lsp-kotlin` externe hors audit (même statut que
  `lsp-host` côté feature:editor).
- **Exception ktlint canonique des projets Compose** :
  `ktlint_function_naming_ignore_when_annotated_with = Composable` posée
  dans le `.editorconfig` racine ET relayée dans le build-logic (les
  @Composable du registre suivent la convention PascalCase du framework,
  sans quoi CHAQUE composable porterait un @Suppress) ; le relais
  `max_line_length = 120` accompagne l'override — la découverte du
  `.editorconfig` n'est pas assurée quand un override existe, sans ce
  relais ktlint reformaterait le dépôt à SA marge par défaut.
- **codeide-lsp 1.3.0 → 1.4.0** : la façade expose la découverte des
  previews (frontière D6 du frère respectée : l'arbre d'appels reste
  NEUTRE, l'interprétation vit côté CodeIDE).
- **52 tests nouveaux** : 45 JVM dans `:preview:compose` (contrats,
  convertisseur, moteur, isolation, transport out-of-process, service,
  rendu pixel NATIF) + 7 Robolectric dans feature:editor (contrat B27 vu
  de CodeIDE : garde Kotlin-seul, onglets du dernier valide, badge, FIT
  centré pixel-vérifié, messages honnêtes, branchement/soumission/
  ré-activation, refermeture-réouverture au même caret).
- **La carte est STATIQUE C1** : le clic n'est pas transmis (boutons en
  no-op honnête), les ressources et thèmes du PROJET ne sont pas encore
  résolus dans la composition (C2 — les couleurs/material par défaut du
  runtime se rendent), l'extraction bounds/fichier:ligne pour le
  surlignage est C2, l'interactif et le watchdog complet sont C3.
- **La persistance de la barre de taille de la carte** relève du
  pipeline Réglages (chantier ultérieur documenté au CHANGELOG).
