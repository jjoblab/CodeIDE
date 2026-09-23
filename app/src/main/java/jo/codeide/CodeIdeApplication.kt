package jo.codeide

import android.app.Application
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp
import jo.codeide.core.crash.AppProcess
import jo.codeide.core.crash.CrashHandler
import jo.codeide.core.crash.DeviceSnapshot
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.LogVerbosityApplier
import jo.codeide.core.domain.RecordPendingExitInfosUseCase
import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.logging.CodeIdeAppLogger
import jo.codeide.core.logging.LoggingInitializer
import jo.codeide.core.model.CrashAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application CodeIDE — point d'assemblage de Hilt (section 5.1),
 * **sensible au processus** (section 5.8).
 *
 * Deux existences, un seul code :
 * - **processus principal** : le gestionnaire de plantages s'installe en
 *   toute première ligne (avant Hilt), la journalisation démarre (en-tête
 *   de session, `FileSink` dans ce processus uniquement), les sorties non
 *   traitées (`ApplicationExitInfo`) sont enregistrées au démarrage ;
 * - **processus `:crash`** (écran dédié) : gestionnaire « sûr » qui ne
 *   relance jamais l'écran, et **aucune** initialisation d'exécution — ni
 *   `FileSink` démarré, ni Room ouverte, ni DataStore lue (section 5.8).
 *   Le graphe Hilt reste assemblé par `super.onCreate()` (inévitable pour
 *   une classe `Application` partagée), mais aucune de ces dépendances
 *   n'y effectue d'I/O avant usage.
 *
 * La lambda `deviceInfo` reste évaluée paresseusement : l'installation
 * elle-même ne fait aucune I/O.
 *
 * LeakCanary (debug) s'installe tout seul via `debugImplementation`.
 */
@HiltAndroidApp
class CodeIdeApplication : Application() {
    @Inject
    lateinit var loggingInitializer: LoggingInitializer

    @Inject
    lateinit var loggerMaison: CodeIdeAppLogger

    @Inject
    lateinit var dispatchers: DispatcherProvider

    @Inject
    lateinit var enregistrerSortiesNonTraitees: RecordPendingExitInfosUseCase

    /** Paramètres persistés — source du niveau de journalisation (étape 4). */
    @Inject
    lateinit var parametres: SettingsRepository

    /** Point de bascule du niveau de journalisation — port du domaine, implémenté par core:logging (façade interne). */
    @Inject
    lateinit var applierNiveau: LogVerbosityApplier

    /**
     * Gestionnaire de plantages du processus principal — porté par
     * l'application pour que `MainActivity` y informe le pisteur du
     * dernier écran (destination de navigation).
     */
    internal var gestionnairePlantages: CrashHandler? = null
        private set

    /** Portée des travaux de démarrage (enregistrement des sorties). */
    private val porteeDemarrage by lazy { CoroutineScope(SupervisorJob() + dispatchers.io) }

    override fun onCreate() {
        // Section 5.8 : le gestionnaire s'installe AVANT toute
        // initialisation — super.onCreate() assemble Hilt, il vient donc
        // après. La détection de processus ne coûte aucune I/O.
        val processus = AppProcess.detect(this)
        when (processus) {
            AppProcess.MAIN -> {
                gestionnairePlantages =
                    CrashHandler.install(this, infosBuild()) {
                        DeviceSnapshot.depuisContext(this)
                    }
            }

            AppProcess.CRASH -> {
                CrashHandler.installSafe()
            }

            AppProcess.OTHER -> {
                Unit
            }
        }

        super.onCreate()

        when (processus) {
            AppProcess.MAIN -> initialiserProcessusPrincipal()
            AppProcess.CRASH, AppProcess.OTHER -> Unit
        }
    }

    /**
     * Informe le pisteur du dernier écran connu — appelé par l'écouteur de
     * navigation de `MainActivity` (le libellé de destination est plus
     * précis que le nom de l'activité).
     *
     * @param ecran libellé de la destination affichée.
     */
    internal fun ecranAffiche(ecran: String) {
        gestionnairePlantages?.onNavigatedTo(ecran)
    }

    /** Initialisation complète — processus principal uniquement. */
    private fun initialiserProcessusPrincipal() {
        if (BuildConfig.DEBUG) {
            activerStrictMode()
        }

        loggingInitializer.initialize()

        // Liaison avec `core:logging` sans dépendance de module (section
        // 5.8) : identifiant de session, filons de pain, vidage borné.
        gestionnairePlantages?.brancherJournalisation(
            sessionId = { loggerMaison.sessionId },
            breadcrumbs = { loggerMaison.breadcrumbs(FILONS_RAPPORT) },
            flush = { delai -> loggerMaison.flushBlocking(delai) },
        )

        // Détection au démarrage : ANR et plantages natifs de la session
        // précédente, hors thread principal (section 5.8).
        porteeDemarrage.launch {
            val crees = enregistrerSortiesNonTraitees()
            if (crees > 0) {
                loggerMaison.i(TAG) { "sorties de processus enregistrées au démarrage : $crees" }
            }
        }

        // Branchement du niveau persisté (étape 4, section 5.7) :
        // AppSettings.logLevel devient la source de vérité du moteur —
        // collecte dans la portée de démarrage du processus principal
        // uniquement (le processus :crash ne lit jamais les paramètres).
        porteeDemarrage.launch {
            parametres.observeSettings().collect { reglages ->
                applierNiveau.apply(reglages.logLevel)
            }
        }
    }

    /**
     * Identité du build pour les rapports (section 5.8) — directement depuis
     * `BuildConfig` : l'installation précède l'injection Hilt, et ces
     * constantes de compilation ne se lisent nulle part ailleurs.
     */
    private fun infosBuild(): CrashAppInfo =
        CrashAppInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            buildType = if (BuildConfig.DEBUG) "debug" else "release",
            applicationId = BuildConfig.APPLICATION_ID,
        )

    /**
     * Active StrictMode avec journalisation (jamais de crash en debug pour
     * une violation : les rapports partent dans le logcat et sont relevés
     * lors des recettes — dont le critère d'étape 2 : la journalisation ne
     * fait aucune I/O sur le thread principal).
     */
    private fun activerStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
    }

    private companion object {
        const val TAG = "App"

        /** Filons joints à un rapport de plantage (section 5.8 : 50). */
        const val FILONS_RAPPORT = 50
    }
}
