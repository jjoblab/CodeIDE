# Questions à trancher en L0 — chantier « Centre de sorties »

> Conformément au § 9 du prompt : l'agent propose, avec recommandation
> justifiée, pour que le propriétaire arbitre **d'un mot**. La sortie de
> L0 attend un « GO L1 » après arbitrage.
> Différence avec le prompt d'origine : les ADR sont renumérotés
> 0159–0162 (créneaux 0153–0158 pris par le chantier générateur —
> `docs/SORTIES_AUDIT.md` § 9).
>
> **Aller-retour du 2026-11-10** : après relecture de la maquette, le
> propriétaire a arbitré DEUX points supplémentaires (Q11 et Q12) — le
> header du panneau et la progression des téléchargements. Les livrables
> L0 (maquette, spec, ADR 0162) ont été amendés en conséquence.

| # | Question | Recommandation de l'agent | Ce qu'il faut arbitrer |
|---|---|---|---|
| 1 | Où vit l'éditeur dédié ? | Onglet central **et** plein écran **et** volet tablette ; panneau compact conservé comme fenêtre sur le MÊME composant (ADR 0162 § 2) | GO / amendement |
| 2 | Moteur d'affichage | Décidé par la **mesure** (ADR 0159 + banc `SORTIES_BANC_MESURE.md`) ; recommandation d'implémentation : RecyclerView virtualisé sur `LigneSortie`, sans DiffUtil ; `codeeditor-ui` lecture seule redevient préféré si ses PV tiennent les budgets | GO L2 après PV du banc / choisir l'ordre A↔B des bancs |
| 3 | Module | **Nouveau `feature:sorties`** + contrat visuel dans `core:ui` (ADR 0162 § 1) — `feature:editor` mesure 27 038 lignes Kotlin (confirmé) et est déjà consommé par ailleurs | GO / amendement |
| 4 | Remplacer ou faire coexister les anciens onglets | **Remplacer** Console, Logcat et Journal compact à la parité (L4/L5, suppression dans le même lot) ; **garder** Problèmes tel quel au départ (ADR 0162 § 4) | GO / garder les anciens onglets en douceur |
| 5 | Historique des builds | 10 exécutions (réglable), plafond 20 000 lignes par exécution (réglable), plafond d'octets global, stockage privé, **expurgées à l'écriture** (`LogRedactor`), live jamais altéré, effacement explicite (ADR 0161) | GO / changer les bornes (10 ? 20 000 ?) |
| 6 | Frontières de tâches Gradle | Événements du client (`EtatTacheBuild` — existants, durées serveur) pour les frontières, texte pour le contenu ; le manque (granularité des sauts) demandé au dépôt frère (`DEMANDES_CODEIDE_TOOLING.md` D1) avec dégradation propre (ADR 0160 § 4) | GO / préférer le texte seul en attendant |
| 7 | Couleurs ANSI | OUI — SGR minimal (gras, italique, 8+8 couleurs), parseur pur testé, désactivable (réglage M13) | GO / refuser les ANSI v1 |
| 8 | « Corriger avec l'IA » sur une erreur de build | Réutiliser le contrat existant `ControleurPanneauEditeur` (`corrigerProblemeAvecIa` pré-rempli depuis la ligne cliquée, `expliquerAvecIa` pour la sortie visible) — opt-in IA inchangé, jamais d'envoi automatique (le bandeau M3 montre le bouton, pas une action) | GO / limiter à la sortie entière |
| 9 | Nom visible | **« Sorties »** — mise à jour du glossaire de `docs/TOOLING.md` (« Console » désigne l'ancien onglet) ; libellé FR/EN (Outputs) (ADR 0162 § 5) | GO / autre nom |
| 10 | (nouveau) Syntaxe de requête du filtre | Differer : `FiltreSorties` livre la sémantique complète en L1 ; la syntaxe `tag:… level:… -texte` n'entre en UI qu'en L2 si la maquette la justifie (M9 l'affiche comme option) — évite un parseur de plus avant d'avoir la mesure | GO / trancher tout de suite |
| 11 | (nouveau, **arbitré**) Que devient l'header existant du panneau ? | **Conservé à l'identique** — rang d'onglets (Sorties · Problèmes · Journal · Logcat), indicateur compact, en-tête poignée ; seule évolution : le libellé « Console » devient « Sorties » ; le sélecteur interne ne porte QUE les sources Gradle (Sync · Build · Tâches · Serveur) — Logcat et Journal ne sont pas dupliqués dedans (maquette M1 corrigée, `SORTIES_SPEC.md` § 7.1) | **arbitré par le propriétaire (2026-11-10)** |
| 12 | (nouveau, **arbitré**) Progression des téléchargements pendant Sync/Build | **OUI — à la ligne même** : chaque artefact ouvre SA ligne au départ, mise à jour EN PLACE (≤ 5 événements/s) — µ-jauge + pourcentage si le total est connu (sonde `.part`), molette + compteurs cumulés sinon, ligne FIGÉE à la fin (taille + durée), jamais une ligne par tick ; les octets par artefact en vol demandés au frère (D2) (`SORTIES_SPEC.md` § 4.7, maquette M1/M2/M4 animée) | **arbitré par le propriétaire (2026-11-10)** |

**Rappel du critère de sortie de L0** : le propriétaire a vu la maquette
(`docs/mockups/sorties/sorties.html`) et tranché les questions
ci-dessus. Aucun code de production n'est écrit avant le « GO L1 ».
