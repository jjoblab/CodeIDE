package jo.codeide.core.terminalruntime.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.TerminalSessionRepository
import jo.codeide.core.terminalruntime.DemarreurService
import jo.codeide.core.terminalruntime.DemarreurServiceAndroid
import jo.codeide.core.terminalruntime.FabriqueCoquilles
import jo.codeide.core.terminalruntime.FabriqueCoquillesTermux
import jo.codeide.core.terminalruntime.RegistreSessionsTermux
import jo.codeide.core.terminalruntime.TerminalRuntime
import javax.inject.Singleton

/**
 * Assemblage Hilt du module `core:terminal-runtime` (prompt Terminal-1,
 * section 2.3) : le registre unique sert les **deux** ports —
 * [TerminalSessionRepository] (domaine, métadonnées consommables par
 * `feature:editor`) et [TerminalRuntime] (type Termux, réservé à
 * `feature:terminal`).
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface TerminalRuntimeBindsModule {
    /** Le port TerminalSessionRepository est servi par le registre global. */
    @Binds
    @Singleton
    fun bindTerminalSessionRepository(impl: RegistreSessionsTermux): TerminalSessionRepository

    /** L'API de rendu (type Termux) est servie par le même registre. */
    @Binds
    @Singleton
    fun bindTerminalRuntime(impl: RegistreSessionsTermux): TerminalRuntime

    /** Fabrique des coquilles : sessions Termux réelles. */
    @Binds
    @Singleton
    fun bindFabriqueCoquilles(impl: FabriqueCoquillesTermux): FabriqueCoquilles

    /** Démarrage du service foreground : implémentation Android. */
    @Binds
    @Singleton
    fun bindDemarreurService(impl: DemarreurServiceAndroid): DemarreurService
}
