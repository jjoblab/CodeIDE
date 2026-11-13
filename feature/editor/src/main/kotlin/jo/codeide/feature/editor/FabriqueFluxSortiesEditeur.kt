package jo.codeide.feature.editor

import jo.codeide.feature.sorties.FabriqueFluxSorties
import jo.codeide.feature.sorties.FamilleSorties
import jo.codeide.feature.sorties.FluxSorties
import jo.codeide.feature.sorties.FluxSortiesJournal
import jo.codeide.feature.sorties.FluxSortiesLogcat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La fabrique du flux PAR FAMILLE (lot L4) — l'assemblage des trois
 * sources derrière le port du lecteur :
 *
 * - [FamilleSorties.GRADLE] → [FluxSortiesCentre] (le câble tooling,
 *   routage banc ↔ réel — lot L3) ;
 * - [FamilleSorties.LOGCAT] → [FluxSortiesLogcat] (le pont Binder des
 *   applications exécutées — R3/R4) ;
 * - [FamilleSorties.JOURNAL] → [FluxSortiesJournal] (le dépôt des
 *   journaux applicatifs).
 *
 * L'implémentation vit dans feature:editor (l'arête editor → sorties
 * est assumée depuis le lot L2) et se LIE comme interface publique de
 * feature:sorties ([FabriqueFluxSorties]) : tout consommateur du lecteur
 * — feature:diagnostics inclus — injecte l'INTERFACE sans dépendre de
 * feature:editor (le graphe Hilt est global, les arêtes de compilation
 * restent propres — ADR 0162).
 */
@Singleton
internal class FabriqueFluxSortiesEditeur
@Inject
constructor(
    private val gradle: FluxSortiesCentre,
    private val logcat: FluxSortiesLogcat,
    private val journal: FluxSortiesJournal,
) : FabriqueFluxSorties {
    override fun flux(famille: FamilleSorties): FluxSorties = when (famille) {
        FamilleSorties.GRADLE -> gradle
        FamilleSorties.LOGCAT -> logcat
        FamilleSorties.JOURNAL -> journal
    }
}
