package jo.codeide.feature.editor

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.RechercheMoteur
import jo.codeide.core.domain.RechercheMoteurBalayage
import javax.inject.Singleton

/**
 * Module Hilt fournissant [RechercheMoteur] (ADR 0094).
 * Construit [RechercheMoteurBalayage] — balayage Kotlin pur en flux.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object RechercheMoteurModule {
    @Provides
    @Singleton
    fun provideRechercheMoteur(): RechercheMoteur = RechercheMoteurBalayage()
}
