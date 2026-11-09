package jo.codeide.feature.editor

import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.SourceSortie

/**
 * L'adaptateur de la console actuelle vers le Centre de sorties (ADR 0160
 * § Décision 2, lot L1) — UNE fonction qui projette une
 * [EvenementConsoleTexte.Ligne] sur le modèle unique [LigneSortie], sans
 * perdre ni inventer une information.
 *
 * Il vit ICI et pas dans `core:domain` : la direction des dépendances
 * interdit au domaine de connaître `feature:editor` (les événements et
 * les libellés `TexteTooling` sont internal à ce module). La résolution
 * du libellé reste SOUS CONTRÔLE DE L'APPELANT ([resoudre]) — le modèle
 * ne contient jamais de libellé résolu (discipline
 * `PresentationTooling`) : le Centre de sorties s'ingère dans le flux
 * existant sans court-circuiter la localisation.
 *
 * Projection du canal ([CanalTooling]) vers la famille de source :
 * SYNC→SYNC, BUILD→BUILD, TACHES→TACHES — les trois canaux du tooling
 * Gradle ont chacun leur famille dans le sélecteur unifié.
 *
 * Projection du style ([StyleLigne]) vers le niveau normalisé (ADR 0160
 * — l'échelle unique) : le stderr gradué ERREUR reste ERREUR,
 * l'avertissement APAISEÉ monte en AVERTISSEMENT, les étapes et
 * synthèses de sync sont des INFO, les flux bruts (SORTIE stdout,
 * TELECHARGEMENT) restent INDETERMINE — Gradle n'a pas de sévérité sur
 * son stdout et le filtre ne doit pas les masquer (règle 14 : une ligne
 * non graduable ne viole jamais un plancher).
 *
 * Ce qui ne monte PAS : `EvenementConsoleTexte.Vider` (contrôle de
 * vidange de canal, pas une ligne) — le lecteur (L2) l'interprète lui-même.
 */
internal fun EvenementConsoleTexte.Ligne.versLigneSortie(resoudre: (TexteTooling) -> String): LigneSortie = LigneSortie(
    source = canal.versSourceSortie(),
    message = resoudre(libelle),
    horodatageMs = horodatageMs,
    niveau = style.versNiveauSortie(),
)

/** Le canal du tooling se projette sur sa famille de source du Centre. */
private fun CanalTooling.versSourceSortie(): SourceSortie = when (this) {
    CanalTooling.SYNC -> SourceSortie.SYNC
    CanalTooling.BUILD -> SourceSortie.BUILD
    CanalTooling.TACHES -> SourceSortie.TACHES
}

/** Le style visuel de la console se projette sur le niveau normalisé. */
private fun StyleLigne.versNiveauSortie(): NiveauSortie = when (this) {
    StyleLigne.ERREUR -> NiveauSortie.ERREUR
    StyleLigne.APAISEE -> NiveauSortie.AVERTISSEMENT
    StyleLigne.ETAPE -> NiveauSortie.INFO
    StyleLigne.SYNTHESE -> NiveauSortie.INFO
    StyleLigne.SORTIE -> NiveauSortie.INDETERMINE
    StyleLigne.TELECHARGEMENT -> NiveauSortie.INDETERMINE
}
