package jo.codeide

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.GetLatestUnreviewedCrashReportUseCase
import jo.codeide.core.domain.MarkCrashReportReviewedUseCase
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.ThemeMode
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.applyDynamicColorsIfAvailable
import jo.codeide.navigation.RoutageEcran
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Activité hôte unique de CodeIDE (section 5.4).
 *
 * Porte le [androidx.navigation.fragment.NavHostFragment] et ne fait rien
 * d'autre : chaque écran est un fragment (Onboarding, Home, NewProject,
 * Settings) et toute logique vit dans les ViewModels et le domaine.
 *
 * Depuis v0.31.4 (crash d'appareil réel f2699ac5), l'activité est
 * `singleTop` et accepte un **routage d'écran** ([EXTRA_ECRAN_CIBLE]) :
 * les activités plein écran (terminal, éditeur) relancent cet hôte avec
 * `REORDER_TO_FRONT | SINGLE_TOP` — l'instance existante est remontée
 * sans recréation, l'appelante survit dessous — et l'écran demandé
 * (installation, diagnostic…) s'ouvre dans le graphe.
 *
 * Cycle de démarrage :
 * 1. [installSplashScreen] **avant** `super.onCreate` (API SplashScreen),
 *    retenu jusqu'à la première émission des paramètres : routage et
 *    apparence se décident **sous l'écran de démarrage**, jamais à
 *    découvert ;
 * 2. apparence pilotée par les paramètres (étape 5) : thème via
 *    [AppCompatDelegate.setDefaultNightMode], langue via
 *    [AppCompatDelegate.setApplicationLocales] (ADR 0013), couleurs
 *    dynamiques conditionnelles (ADR 0008) — chaque choix de l'assistant
 *    recrée l'écran, c'est l'aperçu immédiat ;
 * 3. edge-to-edge : la fenêtre s'étend sous les barres système, chaque
 *    fragment absorbe ses insets via `applySystemBarsInsets` ;
 * 4. routage du premier lancement : `isSetupCompleted` faux → l'assistant
 *    remplace l'accueil en racine de la pile (étape 5) ; vrai → accueil ;
 * 5. suivi du dernier écran (destination de navigation) pour les rapports
 *    de plantage — section 5.8 ;
 * 6. boîte de dialogue « rapport non consulté » si la session précédente
 *    s'est mal terminée (section 5.8) — les deux réponses (voir, ignorer)
 *    valent consultation.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var logger: AppLogger

    @Inject
    lateinit var navigator: AppNavigator

    @Inject
    lateinit var dernierNonConsulte: GetLatestUnreviewedCrashReportUseCase

    @Inject
    lateinit var marquerConsulte: MarkCrashReportReviewedUseCase

    /** Paramètres applicatifs — routage du premier lancement et apparence. */
    @Inject
    lateinit var observerParametres: ObserveSettingsUseCase

    /** Vrai dès la première émission des paramètres (libère le splash). */
    private var demarragePret = false

    /** Dernière apparence appliquée — détecte les changements à chaud. */
    private var apparenceAppliquee: AppSettings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !demarragePret }
        super.onCreate(savedInstanceState)

        // Étape 5 : les couleurs dynamiques ne s'appliquent plus ici mais
        // depuis les paramètres (appliquerApparence) — le réglage
        // utilisateur décide, avec aperçu immédiat.
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)

        // Premiers journaux applicatifs (section 5.7) : démarrage et
        // navigation — la session a déjà été ouverte par l'initialiseur.
        logger.i(TAG) { "MainActivity démarrée" }

        // Routage demandé par une activité plein écran (v0.31.4) : consommé
        // après la première émission des paramètres — le routage du premier
        // lancement doit avoir posé la racine (accueil ou assistant) d'abord.
        // Un `onNewIntent` suivra pour les remontées à chaud.
        routageEnAttente = savedInstanceState == null && intent.hasExtra(RoutageEcran.EXTRA_ECRAN_CIBLE)

        // Dernier écran connu des rapports de plantage (section 5.8) : le
        // libellé de destination est plus précis que le nom d'activité.
        // Cast sûr : sous l'application de test Hilt, le pisteur n'existe
        // pas — le suivi devient un no-op, jamais un échec.
        //
        // Le contrôleur passe par le NavHostFragment : la vue conteneur
        // n'expose son contrôleur qu'une fois le fragment démarré, alors
        // que la lecture directe est disponible dès le gonflement.
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        navHost.navController.addOnDestinationChangedListener { _, destination, _ ->
            (application as? CodeIdeApplication)?.ecranAffiche(destination.label?.toString().orEmpty())
        }

        // Menu debug : déplacé dans l'écran Diagnostic à l'étape 12 —
        // plus aucune trace des outils de développement sur l'accueil.

        // Apparence, routage du premier lancement et libération du splash :
        // tout se joue sur la collecte des paramètres.
        observerParametresEtRouter()

        // Boîte de dialogue de la session précédente : uniquement au premier
        // affichage (une recréation — rotation — ne la ramène pas).
        if (savedInstanceState == null) {
            proposerRapportNonConsulte()
        }
    }

    /**
     * Routage demandé par une activité plein écran avant la création
     * complète (consommé après le routage du premier lancement).
     */
    private var routageEnAttente = false

    /**
     * Remontée à chaud d'une activité plein écran : l'instance reçoit
     * l'intention de routage **sans recréation** (`singleTop` +
     * `REORDER_TO_FRONT`) — l'écran demandé s'ouvre dans le graphe vivant.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(RoutageEcran.EXTRA_ECRAN_CIBLE)) {
            routerEcranCible(intent)
        }
    }

    /**
     * Ouvre l'écran demandé par une activité plein écran dans le graphe :
     * l'action dédiée depuis son origine naturelle (animations et pile),
     * sinon la destination directe (globale au graphe). Sans effet si
     * l'écran demandé est déjà affiché — un double routage n'empile rien.
     */
    private fun routerEcranCible(intent: Intent) {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        val controleur = navHost.navController
        when (intent.getStringExtra(RoutageEcran.EXTRA_ECRAN_CIBLE)) {
            RoutageEcran.ECRAN_INSTALLATION -> {
                logger.d(TAG) { "routage externe -> installation des outils" }
                ouvrirEcranRoutage(
                    controleur,
                    destination = R.id.installation,
                    actions =
                        mapOf(
                            R.id.home to R.id.action_home_to_installation,
                            R.id.onboarding to R.id.action_onboarding_to_installation,
                        ),
                )
            }

            RoutageEcran.ECRAN_DIAGNOSTIC -> {
                logger.d(TAG) { "routage externe -> diagnostic" }
                ouvrirEcranRoutage(
                    controleur,
                    destination = R.id.diagnostics,
                    actions = mapOf(R.id.settings to R.id.action_settings_to_diagnostics),
                )
            }

            RoutageEcran.ECRAN_PARAMETRES -> {
                logger.d(TAG) { "routage externe -> paramètres" }
                ouvrirEcranRoutage(
                    controleur,
                    destination = R.id.settings,
                    actions = mapOf(R.id.home to R.id.action_home_to_settings),
                )
            }

            RoutageEcran.ECRAN_ASSISTANT -> {
                logger.d(TAG) { "routage externe -> assistant" }
                ouvrirEcranRoutage(
                    controleur,
                    destination = R.id.onboarding,
                    actions = mapOf(R.id.home to R.id.action_home_to_onboarding),
                )
            }

            RoutageEcran.ECRAN_NOUVEAU_PROJET -> {
                logger.d(TAG) { "routage externe -> nouveau projet" }
                ouvrirEcranRoutage(
                    controleur,
                    destination = R.id.newproject,
                    actions = mapOf(R.id.home to R.id.action_home_to_newproject),
                )
            }
        }
    }

    /**
     * Navigation routée : action dédiée depuis l'origine indiquée,
     * destination directe ailleurs, rien si déjà en place.
     */
    private fun ouvrirEcranRoutage(
        controleur: NavController,
        destination: Int,
        actions: Map<Int, Int>,
    ) {
        val origine = controleur.currentDestination?.id
        when (origine) {
            destination -> Unit
            else -> controleur.navigate(actions[origine] ?: destination)
        }
    }

    /**
     * Collecte les paramètres : applique l'apparence à chaque émission
     * (aperçu immédiat de l'assistant, restauration au démarrage) et,
     * sur la première, libère le splash puis route le premier lancement.
     */
    private fun observerParametresEtRouter() {
        lifecycleScope.launch {
            observerParametres().collect { reglages ->
                appliquerApparence(reglages)
                if (!demarragePret) {
                    demarragePret = true
                    routerPremierLancement(reglages.isSetupCompleted)
                    // Routage demandé avant la création complète (intention
                    // de lancement) : maintenant que la racine est posée.
                    if (routageEnAttente) {
                        routageEnAttente = false
                        routerEcranCible(intent)
                    }
                }
            }
        }
    }

    /**
     * Applique l'apparence persistée : thème et langue idempotents (la
     * recréation n'a lieu qu'au **changement**), couleurs dynamiques
     * appliquées à chaud à l'activation — leur **désactivation** exige
     * une recréation, l'overlay ne se retire pas.
     */
    private fun appliquerApparence(reglages: AppSettings) {
        val precedente = apparenceAppliquee
        apparenceAppliquee = reglages

        AppCompatDelegate.setDefaultNightMode(modeNuit(reglages.themeMode))

        val locales = localesDemandees(reglages.languageTag)
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }

        val dynamiqueChange = precedente != null && precedente.useDynamicColor != reglages.useDynamicColor
        when {
            reglages.useDynamicColor && (precedente == null || dynamiqueChange) -> {
                applyDynamicColorsIfAvailable()
            }

            !reglages.useDynamicColor && dynamiqueChange -> {
                recreate()
            }

            else -> {
                Unit
            }
        }
    }

    /** Mode de nuit appcompat d'un thème applicatif. */
    private fun modeNuit(mode: ThemeMode): Int =
        when (mode) {
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

    /** Locales demandées : vide = suivre le système (ADR 0013). */
    private fun localesDemandees(tag: String): LocaleListCompat =
        if (tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }

    /**
     * Routage du premier lancement (étape 5) : l'assistant remplace
     * l'accueil **en racine** quand `isSetupCompleted` est faux — le
     * retour système y quitte l'application, il ne tombe pas sur un
     * accueil vide. Ne joue qu'une fois par vie de l'activité : après
     * une mort du processus en plein assistant, la pile restaurée y
     * est déjà, et la garde `home` évite tout doublon.
     */
    private fun routerPremierLancement(complete: Boolean) {
        if (complete) return

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        if (navHost.navController.currentDestination?.id != R.id.home) return

        logger.i(TAG) { "premier lancement : ouverture de l'assistant" }
        val options =
            NavOptions
                .Builder()
                .setPopUpTo(R.id.home, inclusive = true)
                .build()
        navHost.navController.navigate(R.id.onboarding, null, options)
    }

    /**
     * Propose le dernier rapport non consulté (section 5.8) : ANR, plantage
     * natif ou exception de la session précédente, s'il n'a été ni ouvert
     * ni ignoré. Les entrées du dépôt sont déjà bornées (20 rapports).
     */
    private fun proposerRapportNonConsulte() {
        lifecycleScope.launch {
            val resume = dernierNonConsulte() ?: return@launch
            logger.i(TAG) { "un rapport non consulté de la session précédente existe" }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.plantage_dialogue_titre)
                .setMessage(
                    getString(
                        R.string.plantage_dialogue_message,
                        resume.exceptionClassName,
                        FORMAT_DATE.format(Instant.ofEpochMilli(resume.timestampMillis)),
                    ),
                ).setPositiveButton(R.string.plantage_dialogue_voir) { dialogue, _ ->
                    dialogue.dismiss()
                    lifecycleScope.launch {
                        marquerConsulte(resume.id)
                        navigator.openCrashReport(resume.id)
                    }
                }.setNegativeButton(R.string.plantage_dialogue_ignorer) { dialogue, _ ->
                    dialogue.dismiss()
                    lifecycleScope.launch { marquerConsulte(resume.id) }
                }.setOnCancelListener {
                    // Fermer le dialogue n'est pas l'ignorer : il reviendra
                    // au prochain démarrage.
                }.show()
        }
    }

    private companion object {
        const val TAG = "App"

        /** Date lisible d'un rapport — fuseau de lecture. */
        val FORMAT_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
    }
}
