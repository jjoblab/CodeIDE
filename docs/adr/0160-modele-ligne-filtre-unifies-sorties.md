# ADR 0160 — Modèle de ligne unique et filtre unifié des sorties (pur JVM)

- Statut : proposé (2026-11-10, lot L0 — branche
  `agent/sorties-l0-conception`). Renuméroté depuis 0154 (créneau
  occupé par le chantier générateur).
- Contexte : audit § 1 (trois modèles, deux échelles de niveaux), § 2 ;
  `docs/SORTIES_SPEC.md` § 3–6 ; atouts confirmés (`FiltreLogcat`,
  `LignesPile.extraire`, `LogRedactor`).

## Contexte

Trois modèles de ligne coexistent sans type commun :
`EvenementConsoleTexte.Ligne` (interne à `feature:editor`, sans
horodatage, style `StyleLigne`), `LigneJournal` (`core:domain` :
horodatage, pid/tid, étiquette, message) et `LogEntry` (`core:model` :
horodatage, session, niveau 4 valeurs, étiquette, thread, message,
exception). Les échelles de niveaux ne se projettent pas 1:1
(`NiveauJournal` : V/D/I/W/E/F ; `LogLevel` : DEBUG/INFO/WARN/ERROR) ;
la console, elle, n'a PAS de niveau du tout (seulement un style de
ligne). Le filtre existe pour le Logcat (`FiltreLogcat`) mais pas pour
la console ni pour le journal compact.

## Décision

1. **`LigneSortie`** — type pur dans `core:domain` (JVM, testé) :
   - `source` : BUILD, SYNC, TACHES, SERVEUR, LOGCAT, JOURNAL ;
   - `identiteSession` / `idExecution` (référence à une exécution — ADR 0161) ;
   - `horodatageMs` (Unix ; absent → horodaté à la réception côté
     domaine, jamais côté UI) ;
   - `niveau` : une échelle normalisée à SEPT valeurs — V, D, I, W, E, F
     (miroir `NiveauJournal`) + `INDETERMINE` pour les lignes de flux
     sans sévérité (le stdout de Gradle n'est ni info ni warning) ;
     projection implicite depuis `LogLevel` : DEBUG→D, INFO→I,
     WARN→W, ERROR→E ; ASSERT/FATAL→F ;
   - `etiquette` (tag logcat, canal tooling, tag journal) ;
   - `message` ;
   - champs Gradle optionnels : `tache` (chemin), `statutTache`,
     `dureeMs` (événements `EtatTacheBuild` — jamais devinés), positions
     `fichier:ligne:colonne` extraites au passage ;
   - `positionsSource` : liste extraite par le parseur (Kotlin `e:`,
     Java, `* Where`) — consommée par le saut au source.
   Aucun libellé résolu dans le modèle (discipline `PresentationTooling` :
   les textes voyagent en ressource et se résolvent au rendu).
2. **Adaptateurs purs** depuis `EvenementConsoleTexte` (canal → source,
   style → niveau : ERREUR→E, APAISEE→W, ETAPE/SYNTHESE→I, SORTIE→
   INDETERMINE), `LigneJournal` (direct) et `LogEntry` (direct) — tous
   testés JVM, aucun Android.
3. **`FiltreSorties`** — pur, JVM, généralise `FiltreSorties` =
   sémantique `FiltreLogcat` étendue : niveau minimal OU ensemble de
   niveaux, texte (sous-chaîne / regex / mot entier / casse / accents —
   pliage NFD hérité de l'ADR 0025), étiquettes à inclure/exclure,
   sources, sessions, plage de temps, syntaxe de requête optionnelle
   (`tag:… level:… -texte`) activée en L2 si la maquette la justifie.
   Repli « aucune correspondance » + `erreurMotif` signalée, jamais
   d'exception.
4. **Parseur de flux Gradle** (pur JVM) : frontières de tâches
   **alignées sur les événements** `EtatTacheBuild` (le texte `> Task`
   ne sert qu'à rattacher le contenu) ; blocs d'échec (FAILURE /
   Where / What went wrong / Try / Caused by) ; verdicts
   (BUILD SUCCESSFUL/FAILED, `N actionable tasks`) ; positions cliquables ;
   parseur ANSI SGR minimal séparé (pur, testé, désactivable).
5. **Tests** : non-régression sur chaque sortie réelle du corpus
   `docs/archive/tooling-scenarios/sorties/` + cas pathologiques
   (ligne 4 000, flux coupé, UTF-8, `\r`, 100 000 lignes) — cf.
   `docs/SORTIES_SPEC.md` § 10.3.

## Alternatives rejetées

- **Fusionner les modèles existants** (modifier `LigneJournal` ou
  `LogEntry`) : changerait les contrats publics consommés ailleurs
  (diagnostics, journalisation) et casserait la frontière
  `core:model`/`core:domain` — rejeté ; les adaptateurs couvrent.
- **Un modèle par surface** (garder trois modèles + un « pont » à trois
  têtes dans l'UI) : perpétue les trois moteurs et les filtres divergents
  — c'est exactement le constat § 4.1 de l'audit ; rejeté.
- **Niveau = enum par source** (parité exacte des échelles) : rend le
  filtre unifié impossible et impose des niveaux inventés pour Gradle ;
  l'échelle unique à 7 valeurs avec `INDETERMINE` est plus honnête
  (les lignes de flux sans sévérité ne prétendent pas être des infos).
- **Parser les frontières de tâches uniquement par regex** : fragile
  (renommage de Gradle, localisation), alors que les événements serveur
  existent avec durées — le texte ne donne que le contenu (§ 9 Q6) ;
  la demande de granularité des sauts part au dépôt frère
  (`docs/DEMANDES_CODEIDE_TOOLING.md`).

## Conséquences

- L1 livre `LigneSortie`, `FiltreSorties`, les trois adaptateurs, les
  deux parseurs, la table de vérité testable — aucun code UI.
- Le tampon du lecteur (L2) stocke des `LigneSortie` : filtre et
  recherche travaillent sur l'index, la projection regroupée est une
  dérivation du même tampon (une seule liste, deux vues).
- `FiltreLogcat` reste inchangé (consommé par le Logcat actuel) ;
  sa sémantique est dupliquée DANS `FiltreSorties`, et l'onglet Logcat
  bascule vers lui au lot L4 — puis `FiltreLogcat` est retiré dans le
  même lot que son remplacement (pas de code mort).
