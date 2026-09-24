package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Cas d'usage « synchroniser le projet » (Tooling G5, §6) : délègue la
 * synchronisation à l'orchestrateur via le port [GradleToolingRepository]
 * pour le dossier réel du projet — la résolution SAF → FUSE est faite par
 * l'appelant (`ResoudreRepertoireProjet`, même traduction que le terminal
 * T6 : une seule source de vérité).
 *
 * Journalisation identifiante (règle 15) : le dossier n'apparaît JAMAIS
 * dans le journal, seulement le fait de synchroniser.
 *
 * Contexte d'exécution attendu : suspendante (requête orchestrateur),
 * hors thread principal.
 */
public class SynchroniserProjetUseCase
    @Inject
    constructor(
        private val tooling: GradleToolingRepository,
        private val journal: AppLogger,
        private val dispatchers: DispatcherProvider,
    ) {
        /** Synchronise le dossier [dossier] (modèles IDE-like, §5.3). */
        public suspend operator fun invoke(dossier: File): AppResult<ResultatSynchronisation> =
            withContext(dispatchers.io) {
                journal.i(TAG) { "synchronisation demandée" }
                tooling.synchroniser(dossier)
            }
    }

/**
 * Cas d'usage « exécuter des tâches Gradle » (Tooling G5, §6) : lance le
 * build et retourne l'identifiant à observer via
 * [GradleToolingRepository.observeBuildOutput] /
 * [GradleToolingRepository.observeBuildState].
 *
 * Le lancement est feu-and-forget côté orchestrateur : l'échec éventuel se
 * lit dans l'état du build (jamais d'exception jusqu'à l'UI, §1.6).
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 */
public class ExecuterTachesUseCase
    @Inject
    constructor(
        private val tooling: GradleToolingRepository,
        private val journal: AppLogger,
        private val dispatchers: DispatcherProvider,
    ) {
        /**
         * Lance les [taches] sur le dossier [dossier].
         *
         * @return identifiant du build à observer.
         */
        public suspend operator fun invoke(
            dossier: File,
            taches: List<String>,
        ): String =
            withContext(dispatchers.io) {
                journal.i(TAG) { "build demandé (${taches.size} tâche(s))" }
                tooling.build(dossier, taches)
            }
    }

/**
 * Cas d'usage « annuler le build courant » (Tooling G5, §6) : pure
 * délégation au port — l'annulation traverse le protocole jusqu'au jeton
 * Gradle (`CancellationTokenSource`, §4.3). Sans effet si le build est
 * déjà terminé.
 */
public class AnnulerBuildUseCase
    @Inject
    constructor(
        private val tooling: GradleToolingRepository,
    ) {
        /** Demande l'annulation du build [buildId]. */
        public operator fun invoke(buildId: String) {
            tooling.cancel(buildId)
        }
    }

/**
 * Cas d'usage « lister les tâches du projet » (Tooling G5, §6) : alimente
 * le sélecteur de l'action « Exécuter » (le protocole documente
 * `TasksResult` comme « prêt à afficher dans un sélecteur »).
 *
 * Contexte d'exécution attendu : suspendante (requête orchestrateur),
 * hors thread principal.
 */
public class ListerTachesProjetUseCase
    @Inject
    constructor(
        private val tooling: GradleToolingRepository,
        private val dispatchers: DispatcherProvider,
    ) {
        /** Liste les tâches du dossier [dossier]. */
        public suspend operator fun invoke(dossier: File): AppResult<List<InfoTache>> =
            withContext(dispatchers.io) {
                tooling.taches(dossier)
            }
    }

private const val TAG = "GradleUseCase"
