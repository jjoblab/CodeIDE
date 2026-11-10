package jo.codeide.feature.editor

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.feature.sorties.FabriqueFluxSorties
import javax.inject.Singleton

/**
 * Le câblage Hilt des flux du Centre de sorties (lots L3-L4) — le port
 * par FAMILLE ([FabriqueFluxSorties], interface publique de
 * feature:sorties) se lie ICI à [FabriqueFluxSortiesEditeur] : le lecteur
 * choisit SA famille à la construction (banc, onglet Logcat, onglet
 * Journal, écran Journaux), les collecteurs vivent process-wide (pompe
 * tooling, pont Binder, dépôt de journaux). Le lecteur ne change pas —
 * c'est le but du port.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DiSortiesEditeur {

    /** La fabrique par famille (GRADLE, LOGCAT, JOURNAL — lot L4). */
    @Binds
    @Singleton
    internal abstract fun fabriqueFluxSorties(
        implementation: FabriqueFluxSortiesEditeur,
    ): FabriqueFluxSorties
}
