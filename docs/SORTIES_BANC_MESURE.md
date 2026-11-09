# Banc de mesure des moteurs d'affichage des sorties (lot L0)

> Livrable L0 n° 4 du chantier « Centre de sorties ». Ce document est le
> **protocole écrit** des mesures à prendre sur appareil par le
> propriétaire, pour trancher l'ADR 0159 (moteur d'affichage). L'agent
> n'a PAS d'appareil : **aucun chiffre d'appareil n'est revendiqué
> ici** ; les seules mesures déjà prises sont les mesures JVM du banc
> parseur (`docs/SORTIES_AUDIT.md` § 8, sandbox Java 21).

## 1. Candidats (ADR 0159 — à mesurer, pas à goûter)

| Candidat | Description | Ce qu'on attend de lui | Ce qu'on craint |
|---|---|---|---|
| **A. `codeeditor-ui` en lecture seule** | la bibliothèque d'édition déjà intégrée (`gradle/libs.versions.toml` : `code-editor = "v4.14.1"`), buffer virtuel, sélection et recherche gratuites | recherche/sélection/repli quasi gratuits ; déjà testée en production (`OuvrirBufferLectureSeule`) | coût mémoire du buffer d'édition, en-tête collant de tâche à composer, lignes dynamiques (append) peut-être hors de son chemin chaud |
| **B. RecyclerView sur modèle de lignes** | `LigneSortie` → ViewHolder recyclés, DiffUtil interdit (leçon ADR 0074) — append par lot, notifications bornées | chemin chaud simple, repli/en-tête collant naturels, modèle déjà éprouvé par le Logcat actuel | coût de layout par lot, hauteur variable (retour à la ligne), recherche hors écran à implémenter |
| **C. Vue personnalisée sur `Canvas`** | dessin direct des lignes visibles sur un tampon indexé | contrôle total, aucune allocation par frame, retour à la ligne et mesures maîtrisés | coût de construction (sélection, TalkBack, marqueurs), risque de réinventer la roue |

## 2. Instrumentation disponible (ne rien réinventer)

- Réglage `instrumentationTransport` (ADR 0152) : arme les sondes T1–T6
  (transport, publication, lecture) **et** T5/T6 côté vue
  (`SondeConsoleVue` : durée du vidage de lot, images perdues via
  `Choreographer`). Les lignes de mesure partent au journal
  `ConsoleVue` / `ConsoleLatence`, exportables via le journal applicatif.
- Banc parseur JVM : `scripts/banc-parseur-sorties/BancParseur.java`
  (déjà exécuté — cf. audit § 8).

Le banc de mesure est un **mode du composant lui-même** : une source
factice (`SourceSortiesFactice`) rejoue des séquences calibrées ;
aucun serveur de build n'est nécessaire pour la mesure d'affichage.

## 3. Séquences de mesure (identiques pour A, B, C)

Chaque séquence est rejouée 5 fois ; on retient la **médiane** et le
**p95** ; l'appareil est en mode « ne pas maintenir », luminosité fixe,
pas d'autres apps ; le profil est toujours le même (clair ET nuit).

| # | Séquence | Volume | Ce qu'on mesure |
|---|---|---|---|
| S1 | **Débit d'ingestion** : source factice émettant 20 lots de 500 lignes/s puis 10 000 lignes/s pendant 30 s | 100 000 lignes au total | T6 images perdues, durée max d'un vidage T5, latence émission→pixel (sonde ADR 0152) |
| S2 | **Ouverture sur tampon plein** : l'éditeur dédié est ouvert sur un tampon déjà rempli (100 000 lignes, 12 480 « chaudes ») | 100 000 | temps jusqu'au premier cadre rendu (paramètre `millisSinceFirstFrame`), mémoire après 5 s de stabilisation |
| S3 | **Recherche** : sous-chaîne (3 567 correspondances) puis regex `e: .*:\d+:\d+:` sur le tampon plein | 100 000 lignes | durée totale hors thread principal, gèle UI (images perdues pendant la recherche), temps d'aller à la 1 000ᵉ correspondance |
| S4 | **Défilement manuel** : fling continu vers le haut puis vers le bas sur tampon plein, retour à la ligne activé | 30 s | images perdues, temps de layout par lot |
| S5 | **Regroupement** : bascule brut → regroupé (500 tâches) sur tampon plein ; repli/dépli de 50 tâches | 100 000 lignes | durée de bascule (une seule liste re-projetée — ADR 0078), rétention mémoire |
| S6 | **Pause/reprise** : 10 s de pause pendant ingestion à 10 000 l/s, puis reprise | 100 000 | latence de la reprise (delta en un lot), cohérence (aucune ligne perdue ni dupliquée — compteur du lecteur) |
| S7 | **Rotation et navigation** : 20 rotations avec recherche active et filtre actif ; aller-retour éditeur dédié ↔ panneau compact | — | état conservé (position, filtre, recherche), temps de re-attach |
| S8 | **Latence perçue de bout en bout** : build réel d'une fixture `docs/tooling-scenarios/fixtures/` via le serveur, sonde ADR 0152 activée | build réel | émission serveur → pixel, comparaison avec la console actuelle (régression interdite — budget `docs/SORTIES_SPEC.md` § 10.2) |

Volumes et séquences sont encodés dans la source factice par un paramètre
unique (menu de diagnostic « banc sorties », carte dans la page de
configuration tooling, réglage opt-in comme l'instrumentation — coût nul
par défaut).

## 4. Protocole d'acceptation des chiffres

1. Chaque PV porte : appareil, version Android, version CodeIDE (build du
   lot), thème, cadence d'émission, séquence.
2. Une mesure est **rejetée** si une ANR est tombée, si le thermique a
   throttling (journal `BatteryManager`), ou si la source factice a perdu
   des lots (le compteur de pertes du lecteur est la vérité).
3. Les chiffres de deux candidats se comparent **à volumes et séquences
   identiques** ; aucune comparaison avec la console actuelle n'est
   valable hors S8 (qui seule passe par le vrai transport).

## 5. Gabarit de PV (à remplir par le propriétaire)

```
PV mesure moteur A/B/C — séquence S#
Appareil : ____________   Android : ____   CodeIDE : 0.____
Thème : clair / nuit      Date : ____-__-__   Exécution n° _/5

Durée du vidage T5 (médiane) : ____ ms      p95 : ____ ms
Images perdues T6 (médiane)  : ____          p95 : ____
Latence émission→pixel (médiane) : ____ ms  p95 : ____ ms
Mémoire lecteur après 5 s : ____ Mo
Premier cadre rendu (S2 uniquement) : ____ ms
Recherche totale (S3) : ____ ms   gèle UI : ____ images
ANR / pertes de lots observées : oui / non (détail : ______)

Verdict subjectif (défilement) : fluide / saccadé / inutilisable
Remarques : ______________________________
```

## 6. Critères de décision chiffrés (ADR 0159)

Le candidat est retenu si TOUT est vrai :

| Critère | Seuil |
|---|---|
| Ingestion (S1, 10 000 l/s) | 0 image perdue visible (médiane), p95 de latence < 2 frames |
| Ouverture (S2) | premier cadre < 300 ms |
| Recherche (S3) | 0 gèle UI ; 100 000 lignes < 2 s hors thread |
| Défilement (S4) | 0 image perdue (p95) |
| Mémoire (S1+S2) | plafonnée, indépendante du volume total |
| Latence de bout en bout (S8) | ≤ console actuelle (régression interdite) |
| Coût de construction (B/C) | estimé en jours-developpeur, plafonné au lot L2 |

Si DEUX candidats tiennent les budgets, le plus simple est retenu
(B avant A avant C), sauf argument de recherche/sélection : le PV doit
alors le documenter.

## 7. Ce que le banc ne mesure PAS (assumé)

- La latence réseau réelle d'un build distant (hors périmètre — le
  serveur est embarqué) ;
- le coût des couleurs dynamiques Material You (harmonisation
  `ThemeHarmonizer` — mesurée à part si un écart est suspecté) ;
- le comportement TalkBack (critères d'acceptation qualitatifs de la
  spec § 10.3, testés par la chaîne de vérification, pas par le banc).
