package jo.codeide.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.PontJournauxApplications
import jo.codeide.core.domain.RegistrePontJournaux
import javax.inject.Singleton

/**
 * Assemblage Hilt du pont de journaux (mission « Exécuter » R2,
 * ADR 0103) : le registre PURE du domaine est lié au port — le service
 * Binder (`ServicePontJournaux`, module `app`) y pousse, l'interface
 * (onglet Logcat, R3) y lit. Un seul registre par processus : les
 * sessions traversent écrans et recompositions.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PontJournauxModule {
    /** Le registre est son implémentation — pure, sans dépendance Android. */
    @Provides
    @Singleton
    fun fournirRegistre(): RegistrePontJournaux = RegistrePontJournaux()

    /** Le port consommé par l'interface EST ce registre. */
    @Provides
    @Singleton
    fun fournirPort(registre: RegistrePontJournaux): PontJournauxApplications = registre
}
