# ADR 0136 — Capacités LSP fermées par défaut et action « Formater »

- **Statut** : **accepté** (phase 1 livrée en v0.167.0 ; phase 2 — montée
  code-editor 3.74.0 — livrée en v0.168.0, vérifiée par la chaîne locale et la CI).
- **Date** : 2026-10-22 (phase 2 : 2026-10-23)
- **Décideurs** : jjoblab
- **Dépend de** : ADR 0028 (éditeur à onglets, UNE EditorView), ADR 0058 (classpath LSP),
  ADR 0066 (réglages Éditeur consommés — sa promesse de réintroduction est tenue), ADR 0115
  (LSP Kotlin), XE1 (LSP XML). **Bibliothèque** : `code-editor` 3.74.0 (consommée ; note de
  passage `docs/MISE-A-JOUR-CODEIDE.md` § 3.74.0 du dépôt frère, vérifiée contre les 4
  conditions §3.1 : tag, workflows verts, JitPack ok, section complète).

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
4. Le serveur Kotlin n'annonçait pas le formatage (`TableCapacitesLsp`, à la rédaction) ; l'action
   devait gérer proprement l'indisponibilité. **Caduc avec codeide-lsp 1.3.0** (consommé en phase 2,
   v0.168.0) : le serveur Kotlin annonce désormais le formatage (RÉ-INDENTATION seule) — le
   message d'indisponibilité ne s'affiche plus que pour un fichier sans serveur ou une capacité
   coupée dans les réglages de langage.

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
- **D4 — Visibilité en DEUX temps (phase 2 LIVRÉE, v0.168.0).** Phase 1 : l'action n'apparaissait
  que si l'onglet actif avait une session de langue vivante ([serveurPretDe] — la même garde que
  le branchement). Phase 2 : pilotée par `canFormat()` — la disponibilité suit le FORMATEUR de la
  vue, plus le proxy de session ; le branchement étant ASYNCHRONE (le langage est posé au thread
  UI à la fin de `connect()`), `CoordonnateurLsp.brancher` reçoit un rappel exécuté APRÈS la pose
  (file FIFO de la vue) qui re-évalue la visibilité. « Formater la sélection » est ajouté là où
  le projet expose les actions de sélection : la PILL flottante de la bibliothèque
  (`SelectionAction`) — la pill n'existe qu'avec une VRAIE sélection (« sélection vide ⇒ entrée
  masquée » vient du contrat), l'entrée est GRISÉE par `canFormatSelection()` (formateur +
  plages), le tap RELAIE `formatSelection` et les refus portent les MÊMES messages que le
  document (`false` refus propre — formateur sans plages ; `null` aucun formateur).
  Le raccourci physique `Ctrl+Shift+K` (`EditorCommands.FORMAT_SELECTION`) vit côté
  bibliothèque — rien à brancher pour un clavier physique.
- **D5 — Version.** Le lot phase 1 portait un palier mineur : `0.167.0` (le dépôt était à
  `0.166.5` — le numéro de départ du prompt, `0.161.0`, datait d'avant les livraisons
  récentes). La phase 2 en porte un second : `0.168.0`.
- **D6 — Options de formatage : le réglage réintroduit + une politique (v0.168.0).**
  `setFormattingOptions(int tabSize, boolean insertSpaces)` attend les DEUX valeurs ; le prompt
  ne nommait que `TailleTabulation` — et l'investigation a révélé que le « réglage persisté »
  N'EXISTAIT PLUS : l'enum avait été supprimée par l'ADR 0066/v0.37.0 puis ressuscitée par
  erreur par le commit d'import « Apply local CodeIDE changes » (57147dbe), sans champ
  `AppSettings`, sans clé DataStore, sans UI. La promesse de l'ADR 0066 (« réintroduits si la
  bibliothèque expose un jour les setters ») est TENUE : le réglage complet revient — champ
  `editorTailleTabulation` (défaut QUATRE), clé `editor_tab_size` (projection tolérante),
  trio 2/4/8 dans la carte Édition (miroir du trio de police), consommation via
  `OptionsEditeur` → `setFormattingOptions`. La NATURE de l'indentation est une
  POLITIQUE, pas un réglage : ESPACES (convention des projets Java/Kotlin Android,
  `OptionsEditeur.indenterAvecEspaces()`) — la largeur reste à l'utilisateur, le dessin de
  l'éditeur reste auto-détecté par fichier (`IndentDetection`), seule la requête LSP est
  concernée.

## Conséquences

- La phase 1 était INDÉPENDANTE de la bibliothèque : livrée et poussée immédiatement (v0.167.0) ;
  les scénarios d'appareil 2 à 4 (fermeture des capacités au changement d'onglet) sont DÉBLOQUÉS
  par la montée 3.74.0 — `docs/TESTS_MANUELS.md` les porte désormais comme vérifiables, avec les
  nouveaux scénarios de la phase 2 (sélection, options, IME 3.73.0).
- La phase 2 a démarré sur la note de passage (§4 du prompt du chantier) : les 4 conditions §3.1
  vérifiées contre le dépôt frère (tag `v3.74.0`, publish.yml SUCCESS, JitPack « ok » pour les
  3 artefacts, section `## 3.74.0` complète). Aucune rupture d'API (tout est additif) ; le point
  d'attention 3.73.0 (IME niveau plateforme — retirer les contournements clavier) a été
  vérifié NON ARMÉ : seul le filtre logcat masque le clavier, hors `EditorView`.
- `TailleTabulation` (core:model) est CONSOMMÉE (v0.168.0) : sa KDoc ne dit plus « persistée
  avant consommation » ; le réglage vit de bout en bout (UI → DataStore → `OptionsEditeur` →
  `setFormattingOptions`). Aucun texte d'écran ne disait « pas encore appliqué » — la rangée
  avait été retirée tout entière par l'ADR 0066, il n'y avait rien à corriger de ce côté.
- Correctif connexe livré avec la phase 2 : l'injection de l'action ✥ Move de la pill
  (`blockDrag`) vivait dans `appliquerReglagesEditeur` — RÉPÉTÉE à chaque émission des réglages
  (`addSelectionAction` ne déduplique PAS) ; les actions de la pill sont désormais posées UNE
  fois à la création (`installerActionsSelection`).
- Défauts hors périmètre découverts : aucun défaut `code-editor`/`codeide-lsp` n'a été corrigé
  ici ; les déclarations factuelles (version réelle du dépôt, numéro d'ADR suivant, interrupteurs
  de capacités non persistés, enum `TailleTabulation` ressuscitée par erreur par le commit
  d'import — corrigée en la consommant pour de bon) sont consignées au rapport.
