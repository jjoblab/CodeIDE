# Rapport de fin de lot L0 — chantier « Centre de sorties »

> Gabarit de l'annexe B du prompt. Lot : **L0 — audit, conception,
> maquette HTML, ADR** (aucun code de production). Branche :
> `agent/sorties-l0-conception` (base `22097a96`, v0.211.0). Date :
> 2026-11-10. Langue : français.

## 1. Fait (par livrable)

| Livrable | Fichier(s) | État |
|---|---|---|
| 1. Audit confirmé | `docs/SORTIES_AUDIT.md` | ✅ — § 4 du prompt revérifié sur le code réel de `22097a96` ; chaque constat porte verdict + preuve (fichier:lignes) ; 4 corrections/nuances (LogLevel = 4 valeurs ; « Expliquer avec IA » et stdin déjà présents dans la console ; événements `EtatTacheBuild` déjà publiés ; numérotation ADR décalée) |
| 2. Maquette HTML | `docs/mockups/sorties/sorties.html` | ✅ — fichier unique autonome (81 Ko, aucune dépendance réseau), jetons réels jour/nuit cités jeton par jeton, 14 écrans M1–M14 avec légendes « ce que ça montre / pourquoi », interactions exigées (source, puces, recherche + compteur + repères, repli de tâches, pause/reprise avec rattrapage, 3 états du panneau, clair/nuit), section « Écarts maquette → réalité » |
| 3. Spécification | `docs/SORTIES_SPEC.md` | ✅ — zones, comportements du lecteur (11 points), projection Gradle, Logcat (parité + ajouts), journal, surfaces, historique/confidentialité, table de vérité, 12 critères fonctionnels + budgets performance + qualité |
| 4. Banc de mesure | `docs/SORTIES_BANC_MESURE.md` + `scripts/banc-parseur-sorties/BancParseur.java` | ✅ — protocole S1–S8 exécutable par le propriétaire, gabarit de PV, critères de décision chiffrés ; banc parseur JVM exécuté (résultats en § 2) |
| 5. ADR | `docs/adr/0159-…`, `0160-…`, `0161-…`, `0162-…` | ✅ — moteur d'affichage (par mesure), modèle de ligne + filtre unifiés, historique/rétention, surfaces/module/navigation ; chacun liste ses alternatives rejetées |
| 6. Questions à trancher | `docs/SORTIES_QUESTIONS_L0.md` | ✅ — 10 questions avec recommandations (les 9 du prompt + la syntaxe de requête différée) |
| (annexe) Demande au frère | `docs/DEMANDES_CODEIDE_TOOLING.md` | ✅ — D1 granularité des statuts de saut (le fichier n'existait pas ; forme imitée de `DEMANDES_CODEIDE_LSP.md`) |

Commits (branche `agent/sorties-l0-conception`) : listés par
`git log --oneline main..agent/sorties-l0-conception`.

## 2. Preuves (commandes lancées et résultats)

- **Dépôt vérifié** : `git log d9b0c61 -1` → « fusion: Phase 2 côté app
  — …, v0.207.0 » ; HEAD local `22097a96` « chore(release): v0.211.0 » ;
  `version.properties` : `VERSION_NAME=0.211.0`.
- **Mesures JVM** (`java scripts/banc-parseur-sorties/BancParseur.java
  docs/archive/tooling-scenarios/sorties/*.log`, OpenJDK 21.0.13) :
  corpus réel 11 fichiers / 1 642 lignes classées en 62 ms au total ;
  premier fichier à froid 5,9 ms (≈ 92 000 lignes/s) ; flux synthétique
  94 001 lignes en 88,2 ms au meilleur des 3 passes (≈ 1 065 000
  lignes/s) ; 6 000 tâches / 2 000 erreurs / 6 000 avertissements
  correctement classées. Lecture : le parseur n'est pas le goulot
  (2–3 ordres de grandeur au-dessus du budget d'ingestion).
- **Comptage `feature:editor`** : `rg --files feature/editor/src/main
  -g "*.kt" | xargs wc -l` → 27 038 lignes Kotlin (35 878 avec XML) —
  le « ≈ 27 000 » du prompt est confirmé.
- **Maquette vérifiée** : script de contrôle (extraction du JS →
  compilation `new Function` : syntaxe OK ; 0 référence réseau) ;
  rendu Playwright (1440×900) clair + nuit + interactions —
  captures contrôlées visuellement ; un bug CSS réel attrapé et corrigé
  pendant la vérification (`[hidden]{display:none!important}` — les
  éléments `hidden` en `display:flex` interceptaient les clics).

## 3. Écarts avec le plan, et pourquoi

1. **ADR renumérotés 0159–0162** au lieu de 0153–0156 : les créneaux
   sont occupés par le chantier générateur (`docs/adr/0153-…` à
   `0158-…`). Règle « ne jamais réutiliser un numéro » appliquée.
2. **`docs/DEMANDES_CODEIDE_TOOLING.md` créé** alors que le prompt le
   suppose existant : il n'existait pas ; créé avec la première demande
   réelle plutôt que d'inventer un chemin fantôme.
3. **Banc parseur ajouté sous `scripts/banc-parseur-sorties/`** (Java,
   sans dépendance) : le § 7 L0 prévoyait de « mesurer ce qui peut
   l'être en JVM » ; rendre le banc re-exécutable par le propriétaire
   est le moyen le plus honnête de le faire — c'est de l'outillage de
   mesure, pas du code de production (aucun module Gradle touché).
4. **La mesure S8 (latence de bout en bout) n'est PAS prétendue** : sans
   appareil, tout chiffre d'appareil serait un mensonge — le protocole
   est écrit, le PV est prêt à remplir.

## 4. Ce qui n'a pas pu être vérifié

- **Appareil** : rien du banc S1–S8 (ingestion, ouverture, recherche,
  défilement, regroupement, pause/reprise, rotation, latence réelle) ;
  les budgets de `SORTIES_SPEC.md` § 10.2 restent des cibles de départ.
- **Chaîne Gradle locale** : aucun module Gradle n'est touché par L0 ;
  `spotlessApply/detekt/checkModuleDependencies/checkUiTokens` portent
  sur les modules Kotlin — le lot L0 ne contient que `docs/`, un script
  Java de mesure et la maquette HTML ; la chaîne complète sera courue
  dès L1 (et la CI porte le reste).
- **TalkBack / échelle 200 % / couleurs dynamiques** : non maquettés,
  listés dans « Écarts maquette → réalité ».

## 5. Décisions à prendre pour le lot suivant (détail dans `SORTIES_QUESTIONS_L0.md`)

1. **Arbitrer les 10 questions** (un mot chacune suffit) — en
   particulier Q2 (ordre des bancs moteurs) et Q10 (syntaxe de requête
   différée ou non).
2. **Voir la maquette** et noter les corrections d'interface attendues
   (le fichier est autonome : ouverture directe dans un navigateur).
3. **GO L1** : modèle, filtre, adaptateurs, parseur Gradle + ANSI, table
   de vérité testable — JVM pur, aucune UI.
4. (post-L0, à l'intégration) : réponse attendue du dépôt frère sur
   D1 (le chantier dégrade proprement en attendant).

## 6. Risques ouverts

1. **Le dépôt bouge vite** (v0.207.0 → v0.211.0 pendant la préparation
   du prompt) : les constats restent valides sur `22097a96`, mais L1
   re-vérifiera les contrats cités (`EtatTacheBuild`, `FiltreLogcat`,
   `LogRedactor`, port `GradleToolingPort`) avant d'écrire.
2. **Mémoire du lecteur à 100 000 lignes** : la virtualisation règle le
   rendu, pas la rétention du tampon — le plafond et le comportement
   « tronqué annoncé » (M12) sont de la spec, à confirmer au banc S2.
3. **`codeeditor-ui` en lecture seule** : ses capacités réelles
   (append dynamique, en-tête collant) sont supposées, pas mesurées —
   le banc A est écrit pour les éprouver ; si elles tiennent, c'est le
   candidat le moins cher en recherche/sélection.
4. **La table de vérité englobe des types de projets non couverts par le
   corpus** (multiplateforme, Spring) : les libellés sont proposés
   (M14), leur comportement réel sera borné par ce que le serveur expose
   (dégradation propre — même discipline que D1).
5. **Un seul banc de mesure JVM** (Java 21, sandbox) : les chiffres
   Kotlin/JVM de L1 peuvent différer ; le banc sera ré-exécuté en L1
   avec le parseur de production.

## 7. Addendum — aller-retour propriétaire du 2026-11-10 (post-relecture de la maquette)

Le propriétaire a relu la maquette et arbitré deux points ; les
livrables L0 ont été amendés dans le même commit (aucun code de
production, le lot reste L0).

**Q11 — l'header du panneau** (« il y avait déjà un header pour les
sorties du tooling, Problèmes, Journal et Logcat — que devient-il ? »)
- **Arbitrage** : l'header existant du BottomSheet est **conservé à
  l'identique** — rang d'onglets (`OngletPanneau` : Console, Problèmes,
  Journal, Logcat), indicateur compact, en-tête poignée (ADR 0029/0118,
  v0.80.1). Seule évolution : le libellé « Console » devient « Sorties »
  (Q9). La maquette M1 montrait un sélecteur de source interne doublant
  Logcat et Journal — **corrigé** : le sélecteur ne porte que les
  sources Gradle (Sync · Build · Tâches · Serveur) ; Logcat et Journal
  restent des onglets du header, jamais dupliqués dedans (une
  navigation par niveau : le header répond « quelle sortie », le
  sélecteur répond « quel flux Gradle »).
- **Fichiers amendés** : maquette M1 (légende, sélecteur, panneau
  Serveur/Tâches), `SORTIES_SPEC.md` § 7.1, ADR 0162 § 2,
  `SORTIES_QUESTIONS_L0.md` Q11.

**Q12 — progression des téléchargements** (« lors d'un build ou Sync,
la dépendance en cours de téléchargement affiche une petite progression
à la ligne même »)
- **Arbitrage** : OUI — chaque artefact ouvre SA ligne au départ et la
  met à jour EN PLACE (jamais une ligne par tick, throttle serveur
  ≤ 5 événements/s) : µ-jauge + pourcentage si le total est connu
  (sonde `.part`), molette + compteurs cumulés sinon, ligne FIGÉE à la
  fin (taille + durée) — la règle v0.46.0 « une ligne par artefact
  terminé » évolue vers « une ligne par artefact, ouverte au départ,
  conclue en place » (même discipline que les `> Task` conclues en
  place, G9). Plusieurs téléchargements simultanés = une ligne chacun.
- **Honnêteté** : la Tooling API ne livre les octets d'une dépendance
  qu'à sa fin (vérifié `javap`, § v4 de `TOOLING.md`) — un pourcentage
  par artefact en vol exige la généralisation de la sonde `.part`,
  demandée au dépôt frère (**D2**, `DEMANDES_CODEIDE_TOOLING.md`) ;
  d'ici là, dégradation propre (molette + cumuls), jamais de
  pourcentage inventé.
- **Fichiers amendés** : maquette M1/M2/M4 (lignes de téléchargement
  animées, JS sans dépendance, `prefers-reduced-motion` respecté),
  « Écarts maquette → réalité », `SORTIES_SPEC.md` § 4.7,
  `SORTIES_QUESTIONS_L0.md` Q12, `DEMANDES_CODEIDE_TOOLING.md` D2.

**Ce qui reste ouvert** : les arbitrages Q1 à Q10 restent attendus pour
la sortie de L0 ; D1 et D2 attendent la réponse du dépôt frère (le
chantier dégrade proprement en attendant).
