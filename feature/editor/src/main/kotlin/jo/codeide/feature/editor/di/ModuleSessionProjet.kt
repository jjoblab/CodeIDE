package jo.codeide.feature.editor.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ObservateurSessionProjet
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
     * Port d'observation de session (mission « Cycle de vie » étape E) :
     * la projection LECTURE SEULE pour les écrans — l'accueil affiche
     * l'état « projet ouvert » sans dépendre de `:core:session` (le
     * port vit dans `core:domain`, l'implémentation ici, auprès du
     * gestionnaire qu'elle projette). Liaison au composant singleton :
     * résout pour TOUT ViewModel injecté du processus.
     */
    @Provides
    @Singleton
    fun observateurSessionProjet(
        gestionnaireSession: GestionnaireSessionProjet,
        dispatchers: DispatcherProvider,
    ): ObservateurSessionProjet =
        jo.codeide.feature.editor.ObservateurSessionProjetEspace(
            gestionnaireSession = gestionnaireSession,
            dispatchers = dispatchers,
        )

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

    /**
     * Lot LSP-3 (ADR 0128 §4) : table des capacités ANNONCÉES du frère —
     * singleton process-wide (l'écran L2 la lit même éditeur fermé) ;
     * :feature:editor est le seul module qui voit `:lsp:host`, la table
     * n'est JAMAIS recopiée côté écrans (parité verrouillée par test).
     */
    @Provides
    @Singleton
    fun tableCapacitesLsp(): jo.codeide.core.domain.TableCapacitesLsp =
        jo.codeide.feature.editor
            .TableCapacitesLspHote()
}
