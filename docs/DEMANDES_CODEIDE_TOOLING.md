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

## D2 — Ticks d'octets par artefact en vol : µ-jauge honnête sur la ligne de téléchargement ⏳

**Preuve** : les téléchargements traversent déjà (v4 §6,
`EcouteurProgressionCommun` — FILE_DOWNLOAD, débit borné à
5 événements/s par élément) et le client les expose
(`GradleApiImpl.pomperTelechargement`,
`tooling/client/.../GradleApiImpl.kt:685-703` →
`observeTelechargementsBuild`, `GradleToolingPort.kt:161`) avec
`TelechargementBuild(element, octetsRecus, octetsTotal, termine,
dureeMs, compteur)` (`GradleToolingPort.kt:475-490`). Or la Tooling API
ne livre les octets d'une dépendance **qu'à sa FIN** —
`FileDownloadResult.getBytesDownloaded()` n'existe qu'à la fin et le
descripteur d'opération ne porte que l'URI (vérifié par `javap` sur
l'API 9.7.1, tableau § v4 de `docs/TOOLING.md`). En vol, le total
d'artefacts et les octets cumulés sont garantis, mais PAS la répartition
par artefact : la µ-jauge avec pourcentage promise par le propriétaire
(retour du 2026-11-10, maquette M1/M4, `docs/SORTIES_SPEC.md` § 4.7)
ne peut pas être honnête par artefact avec ces seules données.

**Conséquence** : en vol, CodeIDE affiche molette + compteurs cumulés
(n artefacts, X Mo) — jamais un pourcentage inventé (règle 14 /
honnêteté d'interface). Seule la phase Distribution d'une première sync
a un vrai pourcentage aujourd'hui (sonde des fichiers `.part` toutes
les 500 ms, côté serveur).

**Demande** (par ordre de préférence) :
- **D2-a** : généraliser la sonde `.part` aux téléchargements de
  dépendances — pendant chaque FILE_DOWNLOAD en vol, sonder la taille
  du fichier partiel correspondant (cache Gradle
  `modules-2/files-2.1/…/*.part`) au même rythme que le throttle
  actuel (≤ 5 événements/s par élément) et remplir
  `DetailTelechargement.octetsRecus` / `octetsTotal` MID-FLIGHT.
  ADDITIF : un serveur antérieur laisse ces champs à 0/null en vol et
  le client dégrade (molette + cumuls) sans changement de protocole.
- **D2-b** : à défaut de sonde fiable (empêchements de cache),
  renseigner `octetsTotal` à l'ÉVÉNEMENT DE DÉBUT d'un artefact quand
  le Content-Length HTTP est connu (Gradle le possède avant le
  transfert) — la µ-jauge reste en octets reçus cumulés par artefact,
  déjà plus parlante qu'une molette seule.

**Côté CodeIDE** : L2 consomme les champs dès qu'ils existent (µ-jauge +
« 1,2/2,7 Mo · 45 % » à la ligne de l'artefact, mise à jour en place) ;
tolérance de version intégrale — le rendu dégrade vers molette + cumuls
sans aucun changement de code côté client (le test de non-régression du
corpus couvre les deux formes).

## Journal des demandes

| # | Demande | Statut | Livré dans |
|---|---|---|---|
| D1 | Granularité des statuts de saut (UP-TO-DATE / FROM-CACHE / NO-SOURCE) | ⏳ demandé (2026-11-10) | — |
| D2 | Ticks d'octets par artefact en vol (sonde `.part` généralisée ou Content-Length au début) | ⏳ demandé (2026-11-10) | — |
