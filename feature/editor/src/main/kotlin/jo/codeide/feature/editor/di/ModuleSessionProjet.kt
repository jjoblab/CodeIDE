package jo.codeide.feature.editor.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.session.GestionnaireSessionProjet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifie la portée coroutine des FERMETURES de session de projet
 * (mission « Cycle de vie » étape C, ADR 0114 l.90-92) : elle doit
 * SURVIVRE à l'écran demandeur — une fermeture lancée dans le scope
 * d'un ViewModel mourrait AVEC lui et les éléments non annulables
 * (flush des sauvegardes, I7) ne partiraient jamais.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class PorteeFermetureSession

/**
 * Coutures Hilt du contrat de session de projet (mission « Cycle de
 * vie » étape C, ADR 0114). Le contrat vit en Kotlin JVM pur dans
 * `:core:session` — la feature fournit les liaisons Android.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ModuleSessionProjet {
    /**
     * LE gestionnaire process-wide (I1 : au plus une session de projet
     * active) — journal (règle 14) et horloge des rapports de fermeture
     * (section 12.5) injectés.
     */
    @Provides
    @Singleton
    fun gestionnaireSessionProjet(
        journal: AppLogger,
        horloge: TimeProvider,
    ): GestionnaireSessionProjet = GestionnaireSessionProjet(journal = journal, horloge = horloge)

    /**
     * Portée des fermetures : supervisée (une fermeture en échec n'en
     * arrête pas une autre), répartiteur par défaut — survit aux écrans,
     * meurt avec le processus.
     */
    @Provides
    @Singleton
    @PorteeFermetureSession
    fun porteeFermetureSession(dispatchers: DispatcherProvider): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.default)
}
