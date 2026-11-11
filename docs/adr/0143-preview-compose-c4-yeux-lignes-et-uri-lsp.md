# ADR 0143 — Les yeux d'aperçu vivent aux lignes des `@Preview` (façon Android Studio) et la découverte parle l'URI LSP du didOpen (correctif « pas de preview »)

- **Statut** : **accepté** (livré avec CodeIDE v0.177.0).
- **Date** : 2026-10-24
- **Décideurs** : jjoblab (retour propriétaire sur les tests manuels
  v0.176.0 : « le petit icône preview dans son gutter view est visible
  à la ligne où il y a effectivement une preview compose — ce n'est
  pas le cas, et en plus j'ai testé : ça ne marche pas, pas de
  preview »).
- **Dépend de** : ADR 0140 (C1 — l'œil de gouttière et la carte),
  ADR 0141/0142 (C2/C3 — le contenu et l'interactivité), cel-ui
  4.2.0 (B28 : les yeux ancrés), codeide-lsp 1.5.0
  (`PreviewKotlin.ligneAnnotation`), recherche R1 sur la référence
  (`reference/aosp-adt-idea` : `PreviewPickerLineMarkerProvider.kt`
  63-68 — l'ancre du picker est le token « Preview » de l'ANNOTATION,
  un marqueur PAR `@Preview`, AUCUN sans).

## Contexte

Les tests manuels de v0.176.0 (les PREMIERS jamais faits sur la
mission — le propriétaire l'avait annoncé dès C1) ont révélé DEUX
défauts distincts :

1. **L'œil ne vit pas aux bonnes lignes** : cel-ui B27 posait l'œil sur
   la RANGÉE DU CARET pour tout fichier Kotlin prévisualisable (test
   d'extension `canPreview`) — l'icône apparaissait donc PARTOUT et
   NULLE PART, jamais là où une `@Preview` existe. La référence fait
   l'inverse : DEUX marqueurs par fichier Compose (le picker ancré au
   token `Preview` de l'annotation, le run ancré au nom de fonction),
   UN par `@Preview`, et AUCUN marqueur du tout pour un fichier sans
   `@Preview`.
2. **La découverte était MORT-NEE sur les fichiers projet** : les
   onglets vivent en URIS SAF `content://` (« URI, jamais File »,
   ADR 0003), mais la carte `documents` du moteur Kotlin est indexée
   par l'URI du `didOpen` = `"file://" + cheminFUSE`
   (`CoordonnateurLsp.brancher` → `projet.createEditor("file://$
   cheminFuse")` ; `KotlinLanguageServer.didOpen` → `documents[uri]`).
   `HostApercuCompose` interrogeait `previews(uriOnglet)` : la clé
   SAF manque TOUJOURS → liste vide → « Aucune @Preview trouvée ». Les
   tests automatisés C1-C3 passaient car moteur et session étaient
   doublés avec la MÊME chaîne synthétique — l'écart n'était visible
   que sur appareil.

## Décisions

- **D1 — L'ancre de l'œil est la LIGNE DE L'ANNOTATION `@Preview`
  (écho picker AS), calculée par la DÉCOUVERTE** : codeide-lsp 1.5.0
  ajoute `PreviewKotlin.ligneAnnotation` (1-indexée, ligne du `@`,
  arguments multi-lignes compris ; `0` = inconnu) — la marche
  `PreviewsDocumentKotlin` détient déjà le texte, aucune passe
  nouvelle. PAS la ligne du `fun` : c'est le comportement du PICKER de
  la référence (le second marqueur AS — le run — est hors périmètre :
  notre œil EST le picker).
- **D2 — Un œil par `@Preview`, zéro sans (parité AS), par CONTRAT
  cel-ui 4.2.0 ADDITIF (B28)** : `EditorView.setPreviewAnchors` —
  `null` = mode historique B27 au bit près (les hôtes Markdown/HTML
  et la démo ne changent rien) ; VIDE = AUCUN œil ; sinon un œil par
  rangée (triée, dédupliquée, clampée au document au tap). Le mode
  ancré PREND LE PAS sur l'œil du caret (le hit-test historique se
  tait). Le tap sur un œil ouvre la carte SUR CETTE ligne et le hôte
  apprend la ligne (`onPreviewAnchorTapped`, méthode à défaut) — il
  sélectionne l'onglet de LA preview (`selectPreviewDropTab`, miroir
  exact d'un tap d'onglet) ; re-tap sur l'œil gelé = fermeture ; tap
  sur un autre œil = ré-ancre et l'onglet suit.
- **D3 — La découverte parle l'URI LSP du didOpen** : résolveur
  suspend injecté au hôte (miroir `fournisseurProjet` — `cheminFuseDe`
  en IO → `"file://$chemin"`), URI traduite passée au port de
  découverte ; REPLI best-effort sur l'uri d'onglet quand la
  résolution échoue (échantillons, tests à la même clé). La politique
  de repli vit dans le DÉFAUT du port `PortDecouvertePreviews`
  (injectable — `SessionLspKotlin` est une classe FINALE) : URI LSP
  d'abord, uri d'onglet à vide.
- **D4 — Les lignes voyagent par L'ÉTAT, pas par un nouveau canal** :
  `RenduPreviewCompose.ligneAnnotation` (champ en QUEUE, AIDL
  INCHANGÉ) enrichi par le moteur depuis SA découverte (l'indice du
  rendu EST l'indice de la preview ; le pompier C3 reporte la ligne
  de l'ancien onglet). L'hôte publie les ancres : à CHAQUE rendu
  valide, au changement d'onglet (tirage direct SANS attendre un
  bitmap — la machine `:preview` à froid prend des secondes, l'œil
  doit être là avant), à la frappe (débounce 400 ms, miroir moteur,
  écouteur posé sur la session de la VUE — chaque onglet possède la
  sienne), et VIDÉES au changement d'onglet (zéro œil périmé) ;
  `installerSur` REPOUSSE les ancres déjà calculées (onglet actif
  restauré avant `brancherEditeur`).
- **D5 — La marche de découverte vit HORS fil d'UI**
  (`DispatcherProvider` injecté au hôte, miroir moteur : le
  fournisseur vit après le debounce sur le fil du moteur).

## Conséquences

- L'œil du caret disparaît pour les fichiers Kotlin de CodeIDE (il est
  REMPLACÉ par les yeux ancrés) : un `.kt` sans `@Preview` n'a PLUS
  AUCUNE icône de gouttière — c'est la parité AS voulue ; l'action de
  la barre de fil d'Ariane (B6) reste le toggle général (elle ouvre
  la carte, qui répond honnêtement « aucune @Preview »).
- La découverte redevient VÉRITABLE sur les fichiers projet : le bug
  v0.176.0 était STRUCTUREL (clé SAF ≠ clé FUSE) — invisible aux tests
  qui doublaient les deux bords avec la même chaîne ; les nouveaux
  tests C4 doublent la CLÉ et verrouillent la traduction.
- La latence des yeux suit la découverte (brancher + 400 ms de
  frappe), PAS le rendu (le tirage direct pose les yeux avant tout
  bitmap) ; les yeux rafraîchissent au fil des rendus pendant que la
  carte vit.
- Suivis possibles (hors périmètre) : le second marqueur AS (run,
  ancré au nom de fonction — déploiement appareil, hors philosophie
  interpréteur) ; les previews multi-annotations (`@Repeatable`) sur
  une même fonction ; l'œil ancré pour l'APERÇU XML (le frère a son
  écran dédié — inchangé).
