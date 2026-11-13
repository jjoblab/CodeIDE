# PV de séance de mesure appareil — Centre de sorties

> Lot L6, spec SORTIES § 8 (budgets de départ) et § 7 (« séance de
> mesure appareil — PV à remplir par le propriétaire »). Le banc de
> mesure et la sonde d'instrumentation existent depuis le lot L0
> (ADR 0153, ADR 0152) : Paramètres → Avancé → banc « socket seul »
> (`bancSocketSeul` + `bancLignes` / `bancCadenceMs` / `bancLongueurLigne`
> / `bancContenu`), instrumentation du transport (`instrumentationTransport`).
> Ce PV est À REMPLIR PAR LE PROPRIÉTAIRE sur un appareil réel — les
> mesures JVM/JVM-artificielles ne valent pas séance (règle de la
> spéc : « ce que tu n'as pas pu mesurer, dis-le »).

## 1. Conditions de la séance

| Champ | Valeur (à remplir) |
|---|---|
| Date de la séance | … |
| Appareil (modèle, année) | … |
| Android (version) | … |
| Build CodeIDE (VERSION_NAME) | … |
| Mode (nuit / clair) | … |
| Échelle de police système | 1,0 × … |
| Batterie / économiseur | … |

## 2. Budgets à mesurer (spec § 8)

Chaque ligne : la méthode exacte, puis le chiffre relevé, puis le
verdict (✅ tient / ⚠️ tendu / ❌ dépasse).

| # | Budget | Méthode | Relevé | Verdict |
|---|---|---|---|---|
| B1 | **10 000 lignes/s ingérées** sans ANR ni image perdue visible en défilement | banc « socket seul » : `bancLignes=200000`, `bancCadenceMs=0`, lecteur GRADLE ouvert, défilement au doigt collé en bas ; chronométrer le temps d'absorption et observer les images perdues (développeur → profil GPU) | … | … |
| B2 | **Mémoire plafonnée, indépendante du volume total** | même banc à 10 000 puis 200 000 lignes : Mémoire dans les outils développeur — la mémoire du process ne doit pas suivre le volume | … | … |
| B3 | **Aucune allocation par ligne au rendu** | profilage mémoire pendant un défilement rapide (banc 200 000) : pas de pics d'allocations répétés par rangée | … | … |
| B4 | **Ouverture de l'éditeur sur tampon plein < 300 ms** | tampon plein (banc), tuer l'activité, rouvrir — chronométrage vidéo (30 im/s) du tap à la première rangée | … | … |
| B5 | **Recherche sur 100 000 lignes sans geler l'UI** | banc 200 000, recherche d'un motif rare : l'UI reste réactive (le compteur progresse, annulable) | … | … |
| B6 | **Latence d'affichage (émission serveur → pixel) NON RÉGRESSÉE** vs console pré-refonte (ADR 0152, sonde T3/T5/T6) | réglage `instrumentationTransport` ACTIVÉ, séance avant/après sur le même appareil, comparer les médianes exportées dans le journal applicatif | … | … |
| B7 | **Logcat 5 000 (défaut) — réglage 20 000** : défilement fluide et mémoire | réglage « Sorties » → fenêtre Logcat 20 000, pont Binder en charge (app instrumentée), défilement | … | … |

## 3. Mesures d'accessibilité (lot L6)

| # | Contrôle | Méthode | Verdict |
|---|---|---|---|
| A1 | TalkBack : chaque rangée annonce contenu, niveau, état | TalkBack actif, parcours du lecteur GRADLE puis LOGCAT (en-têtes ×N inclus) | … |
| A2 | Échelle 200 % : rien ne coupe | Paramètres système → police 200 %, lecteur + feuille historique + réglages Sorties | … |
| A3 | Nuit / clair : lisibilité plein soleil et de nuit | les deux modes, brièvement à l'extérieur | … |
| A4 | Cibles ≥ 48 dp | barre du lecteur au doigt (pid/tid, historique, erreurs suiv./préc.) | … |

## 4. Écarts constatés et suites

(À remplir : tout budget qui dépasse, la valeur mesurée, et l'ADR ou
l'ajustement proposé — jamais une baisse silencieuse de l'exigence.)

## 5. Signatures

| | |
|---|---|
| Mesures réalisées par (propriétaire) | … |
| Date | … |
| Verdict global (séance conclue / à rejouer) | … |
