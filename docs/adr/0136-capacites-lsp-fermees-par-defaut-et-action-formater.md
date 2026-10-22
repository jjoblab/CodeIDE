# ADR 0136 — Capacités LSP fermées par défaut et action « Formater »

- **Statut** : **proposé** (phase 1 livrée ; passe à « accepté » à la fin de la phase 2, quand la
  montée code-editor 3.74.0 sera consommée et vérifiée).
- **Date** : 2026-10-22
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0028 (éditeur à onglets, UNE EditorView), ADR 0058 (classpath LSP),
  ADR 0115 (LSP Kotlin), XE1 (LSP XML). **Bibliothèque** : `code-editor` 3.72.0 (cible phase 2 :
  3.74.0, note de passage `docs/MISE-A-JOUR-CODEIDE.md` du dépôt frère).

## Contexte

L'éditeur utilise UNE `EditorView` pour tous les onglets. `EditorActivity.brancherLspSiNecessaire`
appelle `CoordonnateurLsp.debrancher()` quand l'onglet actif n'a pas de serveur (ni Java, ni
Kotlin, ni XML, ou session absente). Le principe attendu par le propriétaire : **par défaut, TOUTES
les capacités LSP sont fermées** ; seules celles annoncées par le serveur du type du fichier courant
s'ouvrent.

Constats vérifiés dans le code :

1. **Défaut de la bibliothèque (≤ 3.73.0)** : `LspEditor.disconnect()` n'enlève pas le
   `LspLanguage` de la vue — après un onglet `.java` puis un onglet sans serveur, les capacités
   LSP du fichier Java restent branchées. Corrigé côté bibliothèque en 3.74.0 (l'autre agent).
2. **Aucune action « Formater » au toucher** : la commande clavier physique existe côté
   bibliothèque (`EditorView.formatDocument(Consumer<Boolean>)` — vérifié dans l'artefact 3.72.0),
   rien dans `menu_editeur.xml` ; côté réglages, seule la ligne « Mise en forme du code »
   (`lsp_cap_formatage_desc`) existe, en LECTURE SEULE (interrupteurs différés au chantier
   code-editor, décision LSP-2).
3. **Journal faux** : `CoordonnateurLsp.brancher` étiquetait « Java » TOUT fichier sans extension
   `.kt`/`.kts`/`.xml` (`else -> "Java"`) — un diagnostic trompeur.
4. Le serveur Kotlin n'annonce pas le formatage (`TableCapacitesLsp`) ; l'action doit gérer
   proprement l'indisponibilité.

## Options

- **A. Correction dans la bibliothèque seule** : la déconnexion retire le `LspLanguage`
  (3.74.0) ; CodeIDE n'ajoute AUCUN contournement local — il n'y a rien à contourner, le
  coordinateur appelle déjà `debrancher()` au bon moment.
- **B. Contournement dans le coordinateur** : après déconnexion, retirer la langue de la vue
  depuis CodeIDE (`setLanguage(null)` local).
- **C. Les deux** : contournement immédiat + correction bibliothèque.

## Décision

- **D1 — Option A.** La fermeture des capacités est UN COMPORTEMENT DE BIBLIOTHÈQUE : le cycle de
  vie du `LspLanguage` appartient à cel-lsp. Jusqu'à la montée 3.74.0, l'écart est documenté
  (`docs/TESTS_MANUELS.md`, scénarios 2 à 4 « à vérifier sur appareil » après la montée) ; AUCUN
  `setLanguage(null)` local (le prompt du chantier l'interdit).
- **D2 — Journal honnête (P1-a, livré).** `CoordonnateurLsp.brancher` résout le langage par les
  prédicats d'extension PARTAGÉS avec les onglets (`extensionJava`/`extensionKotlin`/`extensionXml`,
  `langageLspDuFichier`) : un fichier sans extension de langue est journalisé « sans langage
  reconnu — coloration seule », JAMAIS « Java ». Un onglet sans serveur ⇒ `debrancher()` ⇒
  coloration lexicale seule, capacités fermées par la bibliothèque ≥ 3.74.0.
- **D3 — Action « Formater » (P1-b, phase 1 livrée).** Entrée `action_formater` de
  `menu_editeur.xml` (icône `ic_formater` teintée par jeton, `ifRoom`, textes FR/EN). Le clic est
  RELAYÉ par l'activité (règle 1) : la VUE mène (`vueEditeur.formatDocument(callback)`, callback sur
  le thread UI : `true` texte modifié → AUCUN message ; `false` → « Déjà formaté » ;
  `null` → message d'indisponibilité complet — Snackbar). La décision (visibilité, message) est
  PURE ([DecisionFormater], testée JVM). Ne lance rien sans onglet ouvert.
- **D4 — Visibilité en DEUX temps.** Phase 1 : l'action n'apparaît que si l'onglet actif a une
  session de langue vivante ([serveurPretDe] — la même garde que le branchement). Phase 2
  (après note de passage 3.74.0) : pilotée par `canFormat()` ; ajout de « Formater la sélection »
  par `canFormatSelection()`, options de formatage alimentées depuis `TailleTabulation`
  (le réglage devient enfin « consommé »), ADR repassé à « accepté ».
- **D5 — Version.** Le lot porte un palier mineur : `0.167.0` (le dépôt est à `0.166.5` — le
  numéro de départ du prompt, `0.161.0`, datait d'avant les livraisons récentes).

## Conséquences

- La phase 1 est INDEPENDANTE de la bibliothèque : livrée et poussée immédiatement ; les
  scénarios d'appareil 2 à 4 (fermeture des capacités au changement d'onglet) ÉCHOUERONT tant
  que 3.74.0 n'est pas consommée — c'est écrit dans `docs/TESTS_MANUELS.md`.
- La phase 2 ne démarre QUE sur la note de passage (§4 du prompt du chantier) : tag `v3.74.0`
  vérifié, workflows verts, JitPack « ok », section `## 3.74.0` de la note ; sinon ARRÊT et point
  au propriétaire, aucune version montée, AUCUN contournement.
- `TailleTabulation` (core:model) reste « persistée avant consommation » jusqu'à la phase 2 — sa
  KDoc sera corrigée quand le réglage deviendra réel (phase 2).
- Défauts hors périmètre découverts : aucun défaut `code-editor`/`codeide-lsp` n'a été corrigé
  ici ; les déclarations factuelles (version réelle du dépôt, numéro d'ADR suivant, interrupteurs
  de capacités non persistés, absence locale du dépôt frère) sont consignées au rapport.
