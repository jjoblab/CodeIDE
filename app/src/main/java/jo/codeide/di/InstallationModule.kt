package jo.codeide.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.VerificationApprofondie
import jo.codeide.installation.VerificationApprofondieProjets
import javax.inject.Singleton

/**
 * Assemblage Hilt du parcours d'installation côté application (E4 —
 * ADR 0089) : l'implémentation applicative de la vérification approfondie
 * (création d'un projet de contrôle + vrai `assembleDebug`) est liée au
 * port du domaine — l'orchestrateur du parcours (`core:bootstrap`) la
 * consomme par injection, jamais l'inverse.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface InstallationModule {
    /** Vérification approfondie : projet généré + assembleDebug réel. */
    @Binds
    @Singleton
    fun bindVerificationApprofondie(impl: VerificationApprofondieProjets): VerificationApprofondie
}
