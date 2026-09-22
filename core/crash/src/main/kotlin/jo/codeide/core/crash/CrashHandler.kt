package jo.codeide.core.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import jo.codeide.core.crash.ui.CrashActivity
import jo.codeide.core.domain.SystemTimeProvider
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.DeviceInfo
import jo.codeide.core.model.LogEntry
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Lance l'écran dédié après un plantage — injecté pour les tests. */
public fun interface ActivityLauncher {
    /**
     * Lance `CrashActivity` en mode `LIVE` pour ce rapport.
     *
     * @param reportId identifiant du rapport écrit.
     * @throws RuntimeException si le lancement est impossible — le
     * gestionnaire délègue alors au système.
     */
    public fun launchCrashScreen(reportId: String)
}

/** Tue le processus après un plantage géré — injecté pour les tests. */
public fun interface ProcessKiller {
    /** `Process.killProcess` puis `exitProcess(10)` en production. */
    public fun killAndExit()
}

/**
 * Gestionnaire de plantages du processus principal (section 5.8).
 *
 * Enchaînement exigé, tout dans un `try/catch` global avec garde de
 * ré-entrance :
 *
 * 1. détection de boucle (historique persistant, ≥ 3 plantages en 60 s) ;
 * 2. construction du rapport (exception expurgée et bornée, filons, dernier
 *    écran, durée du processus, indicateur de boucle) ;
 * 3. écriture **synchrone et atomique** du rapport ;
 * 4. vidage borné du journal (≤ 500 ms, jamais au-delà du budget total) ;
 * 5. lancement de `CrashActivity`, puis `killProcess` + `exitProcess(10)` ;
 * 6. délégation au gestionnaire précédent si : boucle détectée, lancement
 *    impossible, ou échec interne — jamais après un lancement réussi.
 *
 * La durée totale reste bornée (≤ 2 s) : le vidage est borné par ce qui
 * reste du budget.
 *
 * Le gestionnaire est installé **avant toute initialisation** (première
 * ligne d'`Application.onCreate`, avant Hilt) : sa construction ne fait
 * aucune I/O et ne dépend d'aucun framework d'injection (règle 16). Les
 * lambdas de journalisation sont branchées ensuite par `app` via
 * [brancherJournalisation] — liaison sans dépendance de module.
 *
 * @param inputs entrées applicatives (build, appareil, session, filons).
 * @param fileStore persistance des rapports.
 * @param loopDetector détection de boucle.
 * @param activityLauncher lancement de l'écran dédié (injecté).
 * @param processKiller mort du processus (injecté).
 * @param timeProvider horloge (horodatage du rapport).
 * @param previous gestionnaire précédent, auquel on délègue.
 */
class CrashHandler internal constructor(
    private val inputs: CrashReportInputs,
    private val fileStore: CrashReportFileStore,
    private val loopDetector: CrashLoopDetector,
    private val activityLauncher: ActivityLauncher,
    private val processKiller: ProcessKiller,
    private val timeProvider: TimeProvider,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    private val fabrique = CrashReportFactory(timeProvider)

    /** Garde de ré-entrance : un second plantage pendant la gestion délègue. */
    private val garde = AtomicBoolean(false)

    /** Instant de l'installation — durée du processus dans les rapports. */
    private val debutProcessusNanos = System.nanoTime()

    /**
     * Branche la liaison avec `core:logging` (lambdas fournies par `app`,
     * section 5.8) : identifiant de session, filons de pain et vidage borné.
     *
     * Jusqu'à l'appel, un plantage produit un rapport sans filons ni vidage
     * — dégradation acceptée, jamais un échec.
     */
    fun brancherJournalisation(
        sessionId: () -> String,
        breadcrumbs: () -> List<LogEntry>,
        flush: (Long) -> Unit,
    ) {
        inputs.sessionId = sessionId
        inputs.breadcrumbs = breadcrumbs
        inputs.flush = flush
    }

    /**
     * Informe le pisteur de la destination affichée — appelé par l'écouteur
     * de navigation de `app` (le libellé de destination est plus précis que
     * le nom de l'activité).
     *
     * @param ecran libellé de la destination affichée.
     */
    fun onNavigatedTo(ecran: String) {
        inputs.lastScreenTracker.onNavigatedTo(ecran)
    }

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        if (!garde.compareAndSet(false, true)) {
            deleguer(thread, throwable)
            return
        }
        try {
            val debutNanos = System.nanoTime()

            val boucle = loopDetector.recordAndDetect(timeProvider.nowMillis())
            val rapport =
                fabrique.create(
                    throwable = throwable,
                    threadName = thread.name,
                    inputs = inputs,
                    processUptimeMs = dureeProcessusMs(),
                    isCrashLoop = boucle,
                )
            fileStore.save(rapport)
            viderJournaux(debutNanos)

            if (boucle) {
                // Boucle : le rapport est conservé mais aucun redémarrage
                // n'est proposé — le système reprend la main.
                deleguer(thread, throwable)
                return
            }

            try {
                activityLauncher.launchCrashScreen(rapport.id)
            } catch (erreurLancement: RuntimeException) {
                // Lancement impossible : délégation au gestionnaire système.
                deleguer(thread, throwable)
                return
            }

            // Lancement réussi : pas de délégation — l'écran dédié a pris
            // le relais (conséquences documentées dans l'ADR 0006).
            processKiller.killAndExit()
        } catch (erreurInterne: Throwable) {
            // Le gestionnaire ne doit jamais lui-même planter : délégation.
            deleguer(thread, throwable)
        }
    }

    /** Vidage borné du journal : le reste du budget, plafonné à 500 ms. */
    private fun viderJournaux(debutNanos: Long) {
        val ecouleMillis = (System.nanoTime() - debutNanos) / NANOS_PAR_MILLISECONDE
        val budgetRestant = CrashLimits.HANDLER_BUDGET_MILLIS - ecouleMillis
        val delai = budgetRestant.coerceIn(0, CrashLimits.FLUSH_TIMEOUT_MILLIS)
        inputs.flush(delai)
    }

    /** Durée écoulée depuis l'installation (proche du démarrage). */
    private fun dureeProcessusMs(): Long = (System.nanoTime() - debutProcessusNanos) / NANOS_PAR_MILLISECONDE

    private fun deleguer(
        thread: Thread,
        throwable: Throwable,
    ) {
        previous?.uncaughtException(thread, throwable)
    }

    public companion object {
        /** Conversion nanosecondes → millisecondes (durées mesurées). */
        private const val NANOS_PAR_MILLISECONDE = 1_000_000L

        /**
         * Installe le gestionnaire complet **dans le processus principal**,
         * chaîné avec le gestionnaire précédent.
         *
         * À appeler en toute première ligne d'`Application.onCreate`, avant
         * Hilt : la construction ne fait aucune I/O ni injection.
         *
         * @param application application (l'écouteur d'écran s'y enregistre).
         * @param appInfo identité du build (depuis `BuildConfig`).
         * @param deviceInfo photographie de l'appareil (lambda, repli sûr).
         * @return le gestionnaire installé (pour brancher la journalisation
         * et la navigation après Hilt).
         */
        @JvmStatic
        public fun install(
            application: Application,
            appInfo: CrashAppInfo,
            deviceInfo: () -> DeviceInfo = { DeviceInfo.inconnu() },
        ): CrashHandler {
            val precedent = Thread.getDefaultUncaughtExceptionHandler()
            val directory = application.filesDir.resolve(CrashLimits.DIRECTORY_NAME)
            val pisteur = LastScreenTracker()
            application.registerActivityLifecycleCallbacks(pisteur)
            val gestionnaire =
                CrashHandler(
                    inputs = CrashReportInputs(appInfo, pisteur, deviceInfo),
                    fileStore = CrashReportFileStore(directory),
                    loopDetector = CrashLoopDetector(directory),
                    activityLauncher = IntentActivityLauncher(application),
                    processKiller = DefaultProcessKiller(),
                    timeProvider = SystemTimeProvider(),
                    previous = precedent,
                )
            Thread.setDefaultUncaughtExceptionHandler(gestionnaire)
            return gestionnaire
        }

        /**
         * Installe le gestionnaire **sûr** du processus `:crash` : il ne
         * relance jamais l'écran et n'écrit rien — tout plantage de
         * `CrashActivity` est rendu au système.
         *
         * @param precedent gestionnaire précédent (système, en pratique).
         */
        @JvmStatic
        public fun installSafe(precedent: Thread.UncaughtExceptionHandler? = null): Thread.UncaughtExceptionHandler {
            val existant = precedent ?: Thread.getDefaultUncaughtExceptionHandler()
            val gestionnaire = SafeCrashHandler(existant)
            Thread.setDefaultUncaughtExceptionHandler(gestionnaire)
            return gestionnaire
        }
    }
}

/**
 * Gestionnaire du processus `:crash` (section 5.8) : mode « sûr » — aucune
 * écriture, aucun écran, délégation immédiate au gestionnaire précédent.
 */
private class SafeCrashHandler(
    private val precedent: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        precedent?.uncaughtException(thread, throwable)
    }
}

/** Lancement de production : intent explicite `NEW_TASK | CLEAR_TASK`. */
internal class IntentActivityLauncher(
    private val context: Context,
) : ActivityLauncher {
    override fun launchCrashScreen(reportId: String) {
        val intent =
            Intent(context, CrashActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(CrashActivity.EXTRA_REPORT_ID, reportId)
                .putExtra(CrashActivity.EXTRA_MODE, CrashActivity.MODE_LIVE)
        context.startActivity(intent)
    }
}

/** Mort du processus de production : killProcess puis exitProcess. */
internal class DefaultProcessKiller : ProcessKiller {
    override fun killAndExit() {
        Process.killProcess(Process.myPid())
        exitProcess(CODE_SORTIE_PLANTAGE)
    }

    private companion object {
        /**
         * Code de sortie distinct de zéro (et des codes usuels du
         * framework) : un plantage géré se lit dans les journaux système
         * comme une sortie volontaire du gestionnaire, section 5.8.
         */
        const val CODE_SORTIE_PLANTAGE = 10
    }
}
