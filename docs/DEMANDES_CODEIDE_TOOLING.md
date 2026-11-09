# Demandes à l'agent du dépôt `codeide-tooling` — chantier Centre de sorties (CodeIDE v0.211.0)

> **Note de transmission** : le serveur de build vit dans le dépôt
> frère `codeide-tooling`, consommé en binaire
> (`com.github.jjoblab.codeide-tooling:*`, version épinglée
> `gradle/libs.versions.toml` — voir `settings.gradle.kts`). Ce fichier
> est la voie OFFICIELLE pour lui transmettre les demandes nées du
> chantier « Centre de sorties » — chaque item cite ses preuves
> (fichiers, lignes, journaux). Le statut de chaque demande est à
> tenir à jour ici (⏳ demandé / 🔄 en cours / ✅ livré — avec la
> version codeide-tooling et le lien ADR). En attendant, la solution
> côté CodeIDE **dégrade proprement** (cf. ADR 0160 § décision 4 :
> le texte ne donne que le contenu, les événements donnent les
> frontières).

## D1 — Granularité des statuts de saut de tâche : distinguer UP-TO-DATE / FROM-CACHE / NO-SOURCE ⏳

**Preuve** : le client publie `EtatTacheBuild(buildId, chemin, statut,
dureeMs)` depuis `GradleApiImpl.pomperTacheDemarree` /
`pomperTacheTerminee` (`tooling/client/.../GradleApiImpl.kt:718-747`) ;
`StatutTache` ne connaît que EN_COURS / REUSSIE / ECHOUEE / SAUTEE
(`core/domain/.../GradleToolingPort.kt:487-501`, la doc du type
l'assume : « à jour, désactivée, exclue »). Or le résumé de build du
Centre de sorties (maquette `docs/mockups/sorties/sorties.html` M2,
`docs/SORTIES_SPEC.md` § 4.1) promet : exécutées / **en cache** /
**à jour** / **sans source** — trois états aujourd'hui fusionnés en
« SAUTEE ». Côté protocole, `TaskFinished` porte `skipped` et
`succeeded` mais pas la CAUSE du saut ; le flux `--console=plain`
l'écrit en texte (« UP-TO-DATE », « FROM-CACHE », « NO-SOURCE »), ce
qui n'est pas une source de vérité exploitable pour un compteur.

**Conséquence** : le résumé de build ne peut pas compter honnêtement
« en cache » vs « à jour » : compter sur le texte introduit une
dérivation devinée (interdite par la discipline du présentateur —
ADR 0151) ; ne compter qu'« exécutées / sautées » appauvrit le résumé
promis à l'utilisateur (et la comparaison entre exécutions, ADR 0161).
En attendant la livraison, CodeIDE affiche « sautée » sans désagréger
(jamais un chiffre deviné).

**Demandes** (par ordre de préférence) :
- **D1-a** : enrichir l'événement de fin de tâche d'un champ
  `causeSaut` à trois valeurs fermées — `UP_TO_DATE`, `FROM_CACHE`,
  `NO_SOURCE` — renseigné par la Tooling API (`BuildOperationAncestryTracker`
  / résultats d'opération de tâche), ADDITIF (absent → le client
  continue de traiter tout saut comme SAUTEE).
- **D1-b** : à défaut d'un champ d'événement fiable côté Tooling API,
  publier un événement `TacheResume` par build (une ligne par tâche
  avec statut Gradle complet EXECUTED/UP-TO-DATE/FROM-CACHE/NO-SOURCE/FAILED)
  après `BuildFinished`, dérivé côté serveur (qui voit les opérations,
  pas le texte).
- **D1-c** (annexe) : la durée par tâche est déjà mesurée serveur
  (`TaskFinished.durationMs`) — aucune demande, citée ici pour fixer
  la source de vérité partagée.

**Côté CodeIDE** : D1-a est consommé en L1 (`StatutTache` gagne
`SAUTEE_A_JOUR` / `SAUTEE_CACHE` / `SAUTEE_SANS_SOURCE`, projection vers
le modèle `LigneSortie`) avec tolérance de version (un serveur plus
ancien reste compris) ; le résumé désagrège quand le champ existe.

## Journal des demandes

| # | Demande | Statut | Livré dans |
|---|---|---|---|
| D1 | Granularité des statuts de saut (UP-TO-DATE / FROM-CACHE / NO-SOURCE) | ⏳ demandé (2026-11-10) | — |
