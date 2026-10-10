package jo.codeide.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.ArchiveSessionsJournal
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.PontJournauxApplications
import jo.codeide.core.domain.RegistrePontJournaux
import jo.codeide.logcat.ArchiveSessionsJournalFichiers
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

    /** Archive des sessions terminées (R3, spec § 4.3) : JSON borné dans
     *  filesDir/logcat — dernière ligne et raison de fin consultables
     *  entre deux démarrages de CodeIDE. */
    @Provides
    @Singleton
    internal fun fournirArchive(
        @ApplicationContext contexte: Context,
        dispatchers: DispatcherProvider,
    ): ArchiveSessionsJournal = ArchiveSessionsJournalFichiers(contexte, dispatchers)
}
