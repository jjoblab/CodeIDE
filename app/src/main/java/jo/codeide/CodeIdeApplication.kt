package jo.codeide

import android.app.Application
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp
import jo.codeide.core.crash.AppProcess
import jo.codeide.core.crash.CrashHandler
import jo.codeide.core.crash.DeviceSnapshot
import jo.codeide.core.data.MiroirApparence
import jo.codeide.core.domain.DetecteurChangementEmpreinte
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EmpreinteChaineOutils
import jo.codeide.core.domain.EnvironmentSetupOrchestrator
import jo.codeide.core.domain.EnvironmentSetupState
import jo.codeide.core.domain.LogVerbosityApplier
import jo.codeide.core.domain.RecordPendingExitInfosUseCase
import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.domain.ToolchainLocator
import jo.codeide.core.logging.CodeIdeAppLogger
import jo.codeide.core.logging.LoggingInitializer
import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.ThemeMode
import jo.codeide.core.ui.AppliquerApparence
import jo.codeide.tooling.daemon.DaemonManager
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

    /** Point de bascule du niveau de journalisation — port du domaine,
     * implémenté par core:logging (façade interne). */
    @Inject
    lateinit var applierNiveau: LogVerbosityApplier

    /** Daemon du tooling Gradle (G4) — processus principal uniquement. */
    @Inject
    lateinit var daemonTooling: DaemonManager

    /** Orchestrateur du parcours d'installation (E2-E4, ADR 0087/0089) —
     *  seule source de vérité sur l'environnement depuis E6 (ADR 0091). */
    @Inject
    lateinit var orchestrateurInstallation: EnvironmentSetupOrchestrator

    /** Localisateur de la chaîne d'outils — empreinte pour la relance du daemon (E4, § 6). */
    @Inject
    lateinit var localisateurOutils: ToolchainLocator

    /** Détecteur de changement d'empreinte de la chaîne d'outils (E4, § 6). */
    private val detecteurEmpreinte = DetecteurChangementEmpreinte()

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

        // ADR 0059/0060 — point d'application unique de l'apparence, pour
        // TOUS les processus (chaque processus a son propre onCreate()) :
        // lecture synchrone du miroir (SharedPreferences, pas de
        // coroutine, pas de Hilt), puis mode de nuit et couleurs
        // (dynamiques ou palette statique) posés AVANT super.onCreate()
        // — les activités qui démarrent ensuite (EditorActivity,
        // TerminalActivity, CrashActivity…) héritent du thème sans passer
        // par MainActivity. Le callback de couleurs vit dans
        // AppliquerApparence (core:ui) : il suit le réglage utilisateur,
        // contrairement au callback Material qui teste seulement la
        // capacité de l'appareil (retour utilisateur v0.34 : l'éditeur
        // et le diagnostic gardaient le fond d'écran après désactivation).
        // StrictMode n'est pas encore actif (il s'arme après
        // super.onCreate(), dans le processus principal uniquement).
        val apparence = MiroirApparence.lire(this)
        AppCompatDelegate.setDefaultNightMode(modeNuit(apparence.modeTheme))
        AppliquerApparence.installer(
            application = this,
            couleursDynamiques = apparence.couleursDynamiques,
            palette = apparence.paletteCouleur,
        )

        super.onCreate()

        when (processus) {
            AppProcess.MAIN -> initialiserProcessusPrincipal()
            AppProcess.CRASH, AppProcess.OTHER -> Unit
        }
    }

    /** Mode de nuit appcompat d'un thème applicatif (miroir). */
    private fun modeNuit(mode: ThemeMode): Int =
        when (mode) {
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
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
        // La même émission recopie le miroir d'apparence (ADR 0059) et
        // met à jour l'état coloré (ADR 0060) : une montée de version
        // depuis v0.33.x re-converge le fichier dès le premier
        // démarrage, avant même qu'un réglage ne soit touché.
        porteeDemarrage.launch {
            parametres.observeSettings().collect { reglages ->
                applierNiveau.apply(reglages.logLevel)
                MiroirApparence.ecrire(this@CodeIdeApplication, reglages)
                AppliquerApparence.mettreAJour(
                    couleursDynamiques = reglages.useDynamicColor,
                    palette = reglages.paletteCouleur,
                )
            }
        }

        // Tooling G4 (§5.4) : le daemon de l'orchestrateur Gradle démarre
        // avec le processus principal et vit tant que lui — la fermeture du
        // socket par la mort de l'app termine proprement l'orchestrateur
        // (EOF = fin de boucle, code de sortie 0). JDK absent au démarrage :
        // aucun lancement, l'état reste DECONNECTEE.
        daemonTooling.demarrer(porteeDemarrage)

        // E4 (§ 6, ADR 0089) : le daemon Gradle est RELANCÉ quand
        // l'empreinte de la chaîne d'outils change (JAVA_HOME,
        // ANDROID_HOME, chemin et version d'aapt2, versions installées)
        // — un daemon démarré avec l'ancien environnement garderait ses
        // variables figées et compilerait avec des outils périmés. La
        // première observation n'est pas un changement : le daemon vient
        // de démarrer avec l'environnement courant. Depuis E6 (ADR 0091),
        // ce collecteur couvre AUSSI l'arrivée du JDK en cours de session
        // (l'ancien observateur de `BootstrapInstaller` a été retiré) :
        // le localisateur scanne le disque, l'empreinte change dès que
        // `java`/`javac` existent, le daemon (re)part.
        porteeDemarrage.launch {
            orchestrateurInstallation.state.collect { etat ->
                if (relancerSiEmpreinteChangee(etat)) {
                    daemonTooling.arreter()
                    daemonTooling.demarrer(porteeDemarrage)
                }
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
     * Empreinte courante de la chaîne d'outils, soumise au détecteur —
     * `true` quand elle a changé depuis l'observation précédente (le
     * daemon Gradle doit alors être relancé, § 6 / ADR 0089). Les
     * versions viennent de l'état **vérifié** du parcours, les chemins
     * du localisateur (lui-même alimenté par l'état persisté pour
     * `aapt2`, § 12.4).
     */
    private fun relancerSiEmpreinteChangee(etat: EnvironmentSetupState): Boolean {
        val versions =
            etat.phases.values
                .flatMap { phase ->
                    when (phase) {
                        is jo.codeide.core.domain.PhaseState.Succeeded -> phase.versions.entries
                        else -> emptySet()
                    }
                }.associate { it.key to it.value }
        val empreinte =
            EmpreinteChaineOutils.calculer(
                javaHome = localisateurOutils.javaHome(),
                androidHome = localisateurOutils.androidHome(),
                aapt2 = localisateurOutils.aapt2Binary(),
                versions = versions,
            )
        return detecteurEmpreinte.traiter(empreinte)
    }

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
