package jo.codeide

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.GetLatestUnreviewedCrashReportUseCase
import jo.codeide.core.domain.MarkCrashReportReviewedUseCase
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.applyDynamicColorsIfAvailable
import jo.codeide.debug.MenuDebug
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
 * Cycle de démarrage :
 * 1. [installSplashScreen] **avant** `super.onCreate` (API SplashScreen,
 *    thème `Theme.CodeIDE.Splash` défini dans core:ui) ;
 * 2. couleurs dynamiques optionnelles (Android 12+, ADR 0008) ;
 * 3. edge-to-edge : la fenêtre s'étend sous les barres système, chaque
 *    fragment absorbe ses insets via `applySystemBarsInsets` ;
 * 4. suivi du dernier écran (destination de navigation) pour les rapports
 *    de plantage — section 5.8 ;
 * 5. boîte de dialogue « rapport non consulté » si la session précédente
 *    s'est mal terminée (section 5.8) — les deux réponses (voir, ignorer)
 *    valent consultation.
 *
 * La destination initiale (onboarding ou accueil) dépendra de
 * `isSetupCompleted` à partir de l'étape 5.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        applyDynamicColorsIfAvailable()
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)

        // Premiers journaux applicatifs (section 5.7) : démarrage et
        // navigation — la session a déjà été ouverte par l'initialiseur.
        logger.i(TAG) { "MainActivity démarrée (destination initiale : accueil)" }

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

        // Menu debug (source set `debug` de app, section 5.8) : les builds
        // de développement seules l'installent — la version release
        // embarque un no-op de même signature.
        MenuDebug.installer(this, logger)

        // Boîte de dialogue de la session précédente : uniquement au premier
        // affichage (une recréation — rotation — ne la ramène pas).
        if (savedInstanceState == null) {
            proposerRapportNonConsulte()
        }
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
