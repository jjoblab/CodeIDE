package jo.codeide.core.bootstrap

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.BootstrapInstaller
import jo.codeide.core.domain.DispatcherProvider
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
 * Le diagnostic des 4 ViewModels consommateurs lisaient le localisateur
 * en **instantané pull** (une fois, à la construction, ou à un geste
 * explicite « vérifier ») — les outils installés pendant qu'un écran
 * restait ouvert n'y apparaissaient jamais. Cette implémentation
 * réinterroge le disque à chaque **stimulus** et ne publie que les
 * états réellement changés :
 *
 * - **transitions de l'installateur** : chaque état du pipeline (fin de
 *   la base, fin des paquets d'outils, échec) redéclenche un scan — la
 *   fin d'une installation `apt` est visible immédiatement, sans
 *   attendre le prochain ballotage ;
 * - **ballotage périodique** tant qu'au moins un écran collecte : les
 *   outils peuvent apparaître SANS l'installateur — distribution Gradle
 *   téléchargée par l'orchestrateur du tooling dans
 *   `home/.gradle/wrapper/dists`, SDK Android posé par la commande
 *   `$PREFIX/bin/android-sdk` depuis une session de terminal, `aapt2`
 *   déployé à la première build. Le disque reste la seule source de
 *   vérité, il est réinterrogé — le scan est pur et borné (quelques
 *   tests de fichiers, [LocalisationOutils]).
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
        installateur: BootstrapInstaller,
        private val dispatchers: DispatcherProvider,
    ) : ObserveToolchainStateUseCase {
        private val racine: File = contexte.filesDir

        override fun invoke(): Flow<EtatOutilsTerminal> =
            combine(
                installateur.etat,
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
