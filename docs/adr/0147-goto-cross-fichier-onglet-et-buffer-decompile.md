# ADR 0147 — Le go-to cross-fichier ouvre enfin un ONGLET (fichier projet ou buffer décompilé lecture seule), comme dans Android Studio

- **Statut** : **accepté** (livré avec CodeIDE v0.182.0).
- **Date** : 2026-10-25.
- **Décideurs** : jjoblab (« les go to ne fonctionnent pas, par exemple
  comme dans Android Studio en appuyant sur AppCompatActivity son go
  to devrait ouvrir un nouvel onglet de la classe AppCompatActivity,
  ainsi de suite pour les différents go to »).
- **Dépend de** : ADR 0146 (bump-frère et sa convention), ADR 0143 (uri
  LSP `jdt://` côté code-editor), code-editor v4.2.0 (déjà consommée :
  `EditorView.setOnDefinitionRequestedListener`,
  `jo.codeeditor.lang.model.DefinitionLocation`,
  `LspProject.decompiledSource(ext, fqcn)` — aucune montée de version
  requise), codeide-lsp v1.9.0 (routage fichier → module ADR 0215 —
  les go-to Java servent des cibles JUSTES) et v1.9.1 (complétion
  rapide et honnête).

## Contexte

Le propriétaire navigue par le menu GO TO : appuyer sur
`AppCompatActivity` devrait OUVRIR LA CLASSE dans un nouvel onglet,
comme dans Android Studio. Le chaînage v0.181.0 était pourtant sain de
bout en bout : le serveur rend la Location (v1.9.0 a réparé les cibles
— fichier projet `file:///…` ou type binaire `jdt://decompiled/<fqcn>
.java`), la bibliothèque code-editor reçoit la réponse… et appelait
`onDefinitionRequested` pour toute cible HORS du document courant.
**Personne n'était abonné côté CodeIDE** : le destinataire par défaut
de la bibliothèque étant un no-op, un go-to cross-fichier ne faisait
RIEN (aucun onglet, aucun message) — la navigation intra-fichier
fonctionnait seule. Le buffer décompilé des types binaires était
obtenu (`textDocument/decompiledSource`, capability
`decompiledSourceProvider`) mais restait sans consommateur.

## Options considérées

1. **Consommer le destinataire de la bibliothèque et traduire les
   cibles en actions espace de travail** (retenue) : l'activité pose
   `setOnDefinitionRequestedListener` UNE fois (après l'inflation du
   binding, même contrainte que le fantôme de complétion) et aiguille
   les deux familles d'URI vers deux actions dédiées du ViewModel.
2. Réimplémenter la navigation en interrogeant le serveur directement
   depuis l'activité — écarté : duplique l'état de sélection du caret,
   contourne la bibliothèque qui possède déjà la requête et le menu.
3. Ouvrir les binaires en EXTRACTION vers l'arbre du projet — écarté :
   polluer les sources de l'utilisateur avec des fichiers générés,
   fausse les indexations et la fermeture sale.

## Décision

**Deux actions, deux familles de cibles, un seul destinataire.**

### `OuvrirCibleDefinition` — fichier SOURCE du projet (`file://…`)

Le serveur rend un chemin FUSE absolu ; l'arbre de l'utilisateur vit
en SAF. La traduction RELATIVISE le chemin cible contre la racine FUSE
de la session (`SessionLsp.racine` — le File du projet) puis
reconstruit sous la racine SAF de l'arbre (`uriRacineDe` : le document
suivi du projet, MÊME convention que `sauterVersLigneSource`). Un
chemin hors racine (`..`) est refusé honnêtement (journal). Onglet
déjà ouvert → sélection + saut immédiat ; sinon ouverture via
`ouvrir` puis défilement : l'offset (il pointe la DÉCLARATION dans le
document CIBLE, pas dans le courant) est posé en `sautOffsetEnAttente`
et consommé à l'ajout de l'onglet — l'effet `DefilementVersOffset`
part dès que la session existe, miroir exact du saut de pile Logcat.

### `OuvrirBufferLectureSeule` — type BINAIRE (`jdt://decompiled/…`)

L'activité extrait le FQCN de l'URI, demande la source décompilée au
frère (`CoordonnateurLsp.sourceDecompilee` → `LspProject.
decompiledSource("java", fqcn)`, timeout 5 s côté bibliothèque) et
ouvre un onglet VIRTUEL. Indisponible (serveur absent, FQCN
introuvable) : toast honnête
(`editor_definition_introuvable`), JAMAIS d'écran fantôme. L'URI
`jdt://` est la CLÉ de l'onglet : un second go-to sur le même type
SÉLECTIONNE l'onglet existant au lieu de le dupliquer.

L'onglet virtuel est DELIBÉRÉMENT différent d'un onglet fichier
(`ajouterOngletVirtuel`) :

- **aucun listener de salissure** — il ne peut pas devenir dirty, la
  fermeture ne demande JAMAIS confirmation ;
- **`enregistrer` court-circuite** (vrai honnête, pas d'erreur) ;
- **jamais persisté** — `persisterOnglets` et l'état d'espace le
  filtrent (`virtuelle = true`) : il vit le temps de la session
  d'édition, il n'y a pas de fichier sous-jacent à rouvrir ;
- **chemin affiché `décompilé/<Nom>.java`** — l'explorateur n'a pas ce
  fichier, le chemin sert uniquement au libellé de l'onglet ;
- **jamais branché au LSP** (`serveurPretDe` retourne false) — son URI
  `jdt://` n'a pas de chemin FUSE dans l'arbre du projet : la
  coloration TextMate suffit (langage posé à l'ajout), le silence vaut
  mieux qu'un WARN de résolution à chaque sélection.

### Robustesse

- `EditorTabState.virtuelle` (défaut `false`) : rétrocompatible avec
  les onglets persistés existants.
- `sauterVersOffset` borne l'offset à la longueur du document réel.
- Les URI non gérées (ni `jdt://decompiled/`, ni `file:`) sont
  journalisées sans écran fantôme.

## Conséquences

- Le go-to cross-fichier ouvre l'onglet du fichier projet et SAUTE à
  la déclaration ; le go-to sur un type binaire (`AppCompatActivity`,
  `HashMap`…) ouvre le buffer décompilé en lecture seule — le
  comportement Android Studio demandé.
- La complétion v1.9.1 (frère) n'est PAS impactée : ce lot ne touche
  que la consommation des cibles, jamais la résolution.
- Tests : `GoToDefinitionEditorViewModelTest` (3 scénarios — ouverture
  fichier + saut offset, onglet virtuel + anti-doublon, jamais
  sali/enregistré/persisté + fermeture sans confirmation) ;
  `BaseEditorViewModelTest` gagne la surcharge `viewModel(id,
  resolveurFUSE)` (les deux duplicatas privés identiques de
  `CycleVieEditorViewModelTest` et `LspEditorViewModelTest` sont
  retirés — la base fournit désormais le service).
- Les cibles des AUTRES go-to (typeDefinition, implementations,
  references, superDefinition) transitent par le MÊME destinataire —
  elles héritent du comportement sans code supplémentaire.
