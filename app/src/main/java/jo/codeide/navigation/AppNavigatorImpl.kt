package jo.codeide.navigation

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import jo.codeide.R
import jo.codeide.core.crash.ui.CrashActivity
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.ui.AppNavigator
import jo.codeide.feature.editor.ClesEditor
import jo.codeide.feature.editor.EditorActivity
import jo.codeide.feature.terminal.ClesTerminal
import jo.codeide.feature.terminal.TerminalActivity
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Implémentation applicative de [AppNavigator] (section 5.4) : unique
 * endroit qui connaît le graphe de navigation et les destinations des
 * fonctionnalités.
 *
 * Scopée à l'activité : chaque `MainActivity` obtient la sienne, liée au
 * `NavHostFragment` de son layout.
 *
 * NB : depuis Hilt 2.60, le composant d'activité expose l'`Activity` en
 * instance liée **non qualifiée** (le builder ne porte plus
 * `@ActivityContext`) — on l'injecte donc sans qualificateur.
 */
@ActivityScoped
// Exemption detekt ciblée (règle 16 du prompt maître) : une méthode par
// destination de navigation — l'interface AppNavigator croît par étape,
// chaque override a son objet.
@Suppress("TooManyFunctions")
internal class AppNavigatorImpl
    @Inject
    constructor(
        private val activity: Activity,
        private val logger: AppLogger,
    ) : AppNavigator {
        /** Contrôleur de navigation de l'activité hôte. */
        private val navController: NavController
            get() =
                activity
                    .findViewById<View>(R.id.nav_host_container)
                    ?.findNavController()
                    ?: error("Le conteneur de navigation est introuvable dans MainActivity.")

        override fun openSettings() {
            // Garde-fou : naviguer deux fois d'affilée (double toucher) ferait
            // lever deux fragments ; on ne navigue que depuis l'accueil.
            if (navController.currentDestination?.id == R.id.home) {
                logger.d(TAG) { "navigation accueil -> paramètres" }
                navController.navigate(R.id.action_home_to_settings)
            }
        }

        override fun goBack() {
            navController.popBackStack()
        }

        override fun openCrashReport(id: String) {
            // Intent explicite vers l'écran dédié, en consultation — le
            // processus `:crash` est porté par le manifeste de core:crash
            // (section 5.8) : le système l'isole, aucune action de notre
            // part n'est nécessaire ici.
            logger.d(TAG) { "ouverture du rapport de plantage en consultation" }
            val intention =
                Intent(activity, CrashActivity::class.java)
                    .putExtra(CrashActivity.EXTRA_REPORT_ID, id)
                    .putExtra(CrashActivity.EXTRA_MODE, CrashActivity.MODE_VIEW)
            activity.startActivity(intention)
        }

        override fun openNewProjectWizard() {
            // Garde-fou : un double toucher n'empile qu'un seul wizard.
            if (navController.currentDestination?.id == R.id.home) {
                logger.d(TAG) { "navigation accueil -> nouveau projet" }
                navController.navigate(R.id.action_home_to_newproject)
            }
        }

        override fun openOnboarding() {
            // Garde-fou : un double toucher ne doit empiler qu'un seul
            // assistant — depuis le bandeau de l'accueil (étape 5) ou
            // l'écran Paramètres (étape 6, « relancer »).
            when (navController.currentDestination?.id) {
                R.id.home -> {
                    logger.d(TAG) { "navigation accueil -> assistant" }
                    navController.navigate(R.id.action_home_to_onboarding)
                }

                R.id.settings -> {
                    logger.d(TAG) { "navigation paramètres -> assistant" }
                    navController.navigate(R.id.action_settings_to_onboarding)
                }
            }
        }

        override fun openHome() {
            // Fin de l'assistant (étape 5) : retour à l'accueil en
            // retirant l'assistant de la pile — terminer l'installation
            // n'est pas une navigation réversible.
            logger.d(TAG) { "navigation assistant -> accueil" }
            val options =
                NavOptions
                    .Builder()
                    .setPopUpTo(R.id.onboarding, inclusive = true)
                    .build()
            navController.navigate(R.id.home, null, options)
        }

        override fun wizardCreeProjet(projectId: String) {
            // Création réussie (étape 11) : l'identifiant est déposé sur
            // l'entrée d'accueil de la pile de retour, puis le wizard se
            // referme — l'accueil consommera l'identifiant à son retour.
            logger.d(TAG) { "navigation wizard -> accueil (projet créé)" }
            navController.previousBackStackEntry
                ?.savedStateHandle
                ?.set(CLE_PROJET_CREE, projectId)
            navController.popBackStack()
        }

        override fun consommerProjetCree(): String? {
            // Retire-et-retourne (une seule mise en évidence par création).
            return navController.currentBackStackEntry
                ?.savedStateHandle
                ?.remove(CLE_PROJET_CREE)
        }

        override fun openDiagnostics() {
            // Garde-fou : un double toucher n'empile qu'un seul écran.
            if (navController.currentDestination?.id == R.id.settings) {
                logger.d(TAG) { "navigation paramètres -> diagnostic" }
                navController.navigate(R.id.action_settings_to_diagnostics)
            }
        }

        override fun partagerArchive(
            nomFichier: String,
            emplacementInterne: String,
        ) {
            // Partage d'une archive de diagnostic (étape 12) : le
            // FileProvider n'expose que le répertoire d'export du cache —
            // tout autre chemin est refusé ici plutôt que de lever depuis
            // getUriForFile.
            val fichier = File(emplacementInterne)
            val dossierExports = File(activity.cacheDir, "exports")
            val dansExports =
                try {
                    fichier.canonicalFile.parentFile == dossierExports.canonicalFile
                } catch (e: IOException) {
                    logger.w(TAG, e) { "chemin d'archive non résolu : $emplacementInterne" }
                    false
                }
            if (!dansExports) {
                logger.w(TAG, null) { "partage refusé : archive hors du répertoire d'export" }
                return
            }
            logger.d(TAG) { "partage d'une archive de diagnostic" }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", fichier)
            val intention =
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            activity.startActivity(Intent.createChooser(intention, nomFichier))
        }

        override fun openEditor(projectId: String) {
            // Espace de travail (étape 13) : activité séparée par-dessus la
            // pile — l'accueil survit en dessous, y revenir ne recharge rien.
            logger.d(TAG) { "ouverture de l'espace de travail du projet" }
            val intention =
                Intent(activity, EditorActivity::class.java)
                    .putExtra(ClesEditor.EXTRA_PROJECT_ID, projectId)
            activity.startActivity(intention)
        }

        override fun openBootstrapInstall() {
            // Garde-fou : un double toucher n'empile qu'un seul écran
            // d'installation — depuis le bandeau de l'accueil (étape T3) ou
            // l'étape « Terminal » de l'assistant. L'état partagé du domaine
            // fait le reste : rouvrir n'interrompt ni ne relance rien.
            when (navController.currentDestination?.id) {
                R.id.home -> {
                    logger.d(TAG) { "navigation accueil -> installation des outils" }
                    navController.navigate(R.id.action_home_to_installation)
                }

                R.id.onboarding -> {
                    logger.d(TAG) { "navigation assistant -> installation des outils" }
                    navController.navigate(R.id.action_onboarding_to_installation)
                }
            }
        }

        override fun openTerminal(suggestedWorkingDirectory: String?) {
            // Terminal T5 : activité plein écran par-dessus la pile — les
            // sessions sont globales (registre singleton) et survivent au
            // retour via le service foreground. Le répertoire suggéré
            // transite par l'intent (T6 : accueil et tiroir de l'espace).
            logger.d(TAG) { "ouverture de l'écran du terminal" }
            val intention =
                Intent(activity, TerminalActivity::class.java)
                    .putExtra(ClesTerminal.EXTRA_REPERTOIRE, suggestedWorkingDirectory)
            activity.startActivity(intention)
        }
    }

/**
 * Lie l'implémentation applicative à l'interface `core:ui` : les
 * fonctionnalités n'ont jamais connaissance des autres fonctionnalités.
 */
@Module
@InstallIn(ActivityComponent::class)
internal abstract class NavigationModule {
    @Binds
    internal abstract fun bindAppNavigator(impl: AppNavigatorImpl): AppNavigator
}

private const val TAG = "Navigation"

/** Clé du projet créé, transmise du wizard à l'accueil (étape 11). */
private const val CLE_PROJET_CREE = "accueil.projet_cree"
