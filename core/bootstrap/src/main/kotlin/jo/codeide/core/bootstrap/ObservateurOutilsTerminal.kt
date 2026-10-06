package jo.codeide.core.bootstrap

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EnvironmentSetupOrchestrator
import jo.codeide.core.domain.EnvironmentSetupState
import jo.codeide.core.domain.EtatOutilsTerminal
import jo.codeide.core.domain.ObserveToolchainStateUseCase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation de référence de [ObserveToolchainStateUseCase]
 * (v0.37.3 — retour d'appareil réel : « la plupart des points de l'UI
 * pour le tooling ne se mettent pas à jour »).
 *
 * Le diagnostic des ViewModels consommateurs lisaient le localisateur
 * en **instantané pull** (une fois, à la construction, ou à un geste
 * explicite « vérifier ») — les outils installés pendant qu'un écran
 * restait ouvert n'y apparaissaient jamais. Cette implémentation
 * réinterroge le disque à chaque **stimulus** et ne publie que les
 * états réellement changés :
 *
 * - **transitions du parcours d'installation** (E6 : le stimulus est
 *   l'état de [EnvironmentSetupOrchestrator], l'ancien
 *   `BootstrapInstaller` a été retiré) : chaque changement de phase
 *   (fin du bootstrap, JDK vérifié, SDK posé, échec) redéclenche un
 *   scan — la fin d'une installation est visible immédiatement, sans
 *   attendre le prochain ballotage ;
 * - **ballotage périodique** tant qu'au moins un écran collecte : les
 *   outils peuvent apparaître SANS le parcours — distribution Gradle
 *   téléchargée par l'orchestrateur du tooling dans
 *   `home/.gradle/wrapper/dists`, `aapt2` déployé à la première build.
 *   Le disque reste la seule source de vérité, il est réinterrogé —
 *   le scan est pur et borné (quelques tests de fichiers,
 *   [LocalisationOutils]).
 *
 * Le flot vit derrière `flowOn(dispatchers.io)` : jamais d'I/S disque
 * sur le thread principal, contrairement aux pulls précédents des
 * ViewModels (garde JDK de l'éditeur comprises).
 *
 * `distinctUntilChanged` ferme la sortie : un stimulus sans changement
 * d'état ne réémet rien, les écrans ne re-rendent pas pour rien.
 */
@Singleton
internal class ObservateurOutilsTerminal
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
        orchestrateur: EnvironmentSetupOrchestrator,
        private val dispatchers: DispatcherProvider,
    ) : ObserveToolchainStateUseCase {
        private val racine: File = contexte.filesDir
        private val etatParcours: Flow<EnvironmentSetupState> = orchestrateur.state

        override fun invoke(): Flow<EtatOutilsTerminal> =
            combine(
                etatParcours,
                horlogeBallotage(),
            ) { _, _ -> scanner() }
                .distinctUntilChanged()
                .flowOn(dispatchers.io)

        /**
         * Ballotage tant que la collecte vit : un tic par période, stoppé
         * net par l'annulation de la collecte (personne n'observe →
         * personne ne scanne — `WhileSubscribed` des écrans met fin à la
         * chaîne).
         */
        private fun horlogeBallotage(): Flow<Long> =
            flow {
                var tic = 0L
                while (currentCoroutineContext().isActive) {
                    emit(tic)
                    tic += 1
                    delay(PERIODE_BALLOTAGE_MS)
                }
            }

        /** Rejoue le diagnostic pur du localisateur sur la racine réelle. */
        private fun scanner(): EtatOutilsTerminal =
            EtatOutilsTerminal(
                bootstrapInstalle = LocalisationOutils.bootstrapInstalle(racine),
                jdkInstalle = LocalisationOutils.trouverJavaHome(racine) != null,
                gradleInstalle = LocalisationOutils.trouverGradleHome(racine) != null,
                sdkAndroidInstalle = LocalisationOutils.trouverAndroidHome(racine) != null,
                aapt2Installe = LocalisationOutils.trouverAapt2(racine) != null,
                // v0.39.1 : le drapeau `initialise` signale aux consommateurs
                // (sync d'ouverture) que le disque a été lu au moins une fois —
                // la valeur par défaut `false` ne peut plus être confondue avec
                // « JDK absent » alors que le scan n'a simplement pas encore
                // eu lieu. Toutes les émissions suivantes portent `true`.
                initialise = true,
            )

        private companion object {
            /**
             * Période de ré-interrogation du disque tant qu'un écran
             * collecte : assez courte pour qu'une installation terminée
             * ailleurs (terminal, tooling) soit visible « tout de suite »
             * à l'échelle humaine, assez longue pour rester négligeable
             * (le scan est quelques stat de fichiers).
             */
            private const val PERIODE_BALLOTAGE_MS = 2_000L
        }
    }
