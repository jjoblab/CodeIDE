package jo.codeide.navigation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import jo.codeide.MainActivity
import jo.codeide.R
import jo.codeide.core.crash.ui.CrashActivity
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.ui.AppNavigator
import jo.codeide.core.ui.SectionParametres
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
 * Scopée à l'activité : chaque activité hôte obtient la sienne. Depuis
 * v0.31.4 (crash d'appareil réel f2699ac5), l'implémentation sait que
 * l'hôte n'est pas toujours `MainActivity` — l'écran plein écran du
 * terminal et l'espace de travail injectent aussi un navigateur :
 *
 * - **[goBack] hors graphe** (`TerminalActivity`, `EditorActivity`) :
 *   « ramène-moi là d'où je venais » = terminer l'activité — l'ancien
 *   accès direct au contrôleur levait `IllegalStateException` à
 *   chaque retour depuis le terminal (l'archive ne contient pas de
 *   conteneur de navigation) ;
 * - **navigation vers un écran du graphe** (paramètres, diagnostic,
 *   installation, assistant) depuis une activité sans conteneur :
 *   relance de `MainActivity` avec [RoutageEcran.EXTRA_ECRAN_CIBLE]
 *   et les drapeaux `REORDER_TO_FRONT | SINGLE_TOP` — l'instance
 *   existante est remontée au premier plan **sans être recréée** et
 *   sans détruire l'activité appelante (l'éditeur survit dessous ;
 *   un retour système y revient, état intact) ;
 * - les autres destinations graphiques (accueil du wizard, pile)
 *   restent réservées aux fragments du graphe : hors graphe, elles
 *   se journalisent et ne font rien — jamais de plantage.
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
        /**
         * Contrôleur de navigation de l'activité hôte, ou `null` quand
         * l'hôte est une activité plein écran sans graphe (terminal,
         * éditeur, consultation d'un rapport).
         */
        private val navControllerDHote: NavController?
            get() = activity.findViewById<View>(R.id.nav_host_container)?.findNavController()

        override fun openSettings() {
            naviguerDansLeGraphe(
                actionDepuisAccueil = R.id.action_home_to_settings,
                destination = R.id.settings,
                ecran = RoutageEcran.ECRAN_PARAMETRES,
                journal = "paramètres",
            )
        }

        override fun openSettingsSection(section: SectionParametres) {
            // Les sections ne s'ouvrent QUE depuis le maître des paramètres
            // (ADR 0059 : deux niveaux) — navigation directe par destination,
            // l'écran « bientôt » reçoit la section en argument.
            val controleur = navControllerDHote
            if (controleur == null || controleur.currentDestination?.id != R.id.settings) {
                logger.w(TAG) { "ouverture d'une section des paramètres hors du maître : ignorée" }
                return
            }
            when (section) {
                SectionParametres.IA, SectionParametres.OUTILS, SectionParametres.SECURITE -> {
                    logger.d(TAG) { "navigation paramètres -> section bientôt disponible" }
                    val arguments = Bundle()
                    arguments.putString(CLE_SECTION_BIENTOT, section.name)
                    controleur.navigate(R.id.settings_bientot, arguments)
                }

                else -> {
                    val destination =
                        when (section) {
                            SectionParametres.APPARENCE -> R.id.settings_apparence

                            SectionParametres.LANGUE -> R.id.settings_langue

                            SectionParametres.NOTIFICATIONS -> R.id.settings_notifications

                            SectionParametres.EDITEUR -> R.id.settings_editeur

                            SectionParametres.TERMINAL -> R.id.settings_terminal

                            SectionParametres.PROJETS -> R.id.settings_projets

                            SectionParametres.A_PROPOS -> R.id.settings_apropos

                            SectionParametres.AVANCE -> R.id.settings_avance

                            // Atteints seulement via la branche « bientôt »
                            // ci-dessus — branche conservée pour l'exhaustivité.
                            SectionParametres.IA,
                            SectionParametres.OUTILS,
                            SectionParametres.SECURITE,
                            -> R.id.settings_bientot
                        }
                    logger.d(TAG) { "navigation paramètres -> section" }
                    controleur.navigate(destination)
                }
            }
        }

        override fun goBack() {
            val controleur = navControllerDHote
            if (controleur == null) {
                // Activité plein écran (terminal, éditeur) : le retour
                // referme l'écran — les sessions du terminal survivent
                // via le service foreground, l'éditeur garde son état.
                logger.d(TAG) { "retour : fermeture de l'écran plein écran" }
                activity.finish()
                return
            }
            // Racine du graphe atteinte : le retour remonte d'un niveau
            // système (même geste que le retour système depuis l'accueil).
            if (!controleur.popBackStack()) {
                activity.finish()
            }
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
            naviguerDansLeGraphe(
                actionDepuisAccueil = R.id.action_home_to_newproject,
                destination = R.id.newproject,
                ecran = RoutageEcran.ECRAN_NOUVEAU_PROJET,
                journal = "nouveau projet",
            )
        }

        override fun openOnboarding() {
            // Garde-fou : un double toucher ne doit empiler qu'un seul
            // assistant — depuis le bandeau de l'accueil (étape 5) ou
            // l'écran Paramètres (étape 6, « relancer »).
            when (navControllerDHote?.currentDestination?.id) {
                R.id.home -> {
                    logger.d(TAG) { "navigation accueil -> assistant" }
                    navControllerDHote?.navigate(R.id.action_home_to_onboarding)
                }

                R.id.settings -> {
                    logger.d(TAG) { "navigation paramètres -> assistant" }
                    navControllerDHote?.navigate(R.id.action_settings_to_onboarding)
                }

                else -> {
                    relancerMainActivity(RoutageEcran.ECRAN_ASSISTANT)
                }
            }
        }

        override fun openHome() {
            val controleur = navControllerDHote
            if (controleur == null) {
                logger.w(TAG) { "openHome appelé hors du graphe : ignoré" }
                return
            }
            // Fin de l'assistant (étape 5) : retour à l'accueil en
            // retirant l'assistant de la pile — terminer l'installation
            // n'est pas une navigation réversible.
            logger.d(TAG) { "navigation assistant -> accueil" }
            val options =
                NavOptions
                    .Builder()
                    .setPopUpTo(R.id.onboarding, inclusive = true)
                    .build()
            controleur.navigate(R.id.home, null, options)
        }

        override fun wizardCreeProjet(projectId: String) {
            val controleur = navControllerDHote
            if (controleur == null) {
                logger.w(TAG) { "wizardCreeProjet appelé hors du graphe : ignoré" }
                return
            }
            // Création réussie (étape 11) : l'identifiant est déposé sur
            // l'entrée d'accueil de la pile de retour, puis le wizard se
            // referme — l'accueil consommera l'identifiant à son retour.
            logger.d(TAG) { "navigation wizard -> accueil (projet créé)" }
            controleur.previousBackStackEntry
                ?.savedStateHandle
                ?.set(CLE_PROJET_CREE, projectId)
            controleur.popBackStack()
        }

        override fun consommerProjetCree(): String? {
            // Retire-et-retourne (une seule mise en évidence par création).
            return navControllerDHote
                ?.currentBackStackEntry
                ?.savedStateHandle
                ?.remove(CLE_PROJET_CREE)
        }

        override fun openDiagnostics() {
            val controleur = navControllerDHote
            when (controleur?.currentDestination?.id) {
                R.id.settings -> {
                    logger.d(TAG) { "navigation paramètres -> diagnostic" }
                    controleur.navigate(R.id.action_settings_to_diagnostics)
                }

                null -> {
                    // Depuis l'éditeur (journal complet du tooling, v0.31.4) :
                    // l'écran appartient au graphe de MainActivity.
                    relancerMainActivity(RoutageEcran.ECRAN_DIAGNOSTIC)
                }

                else -> {
                    logger.d(TAG) { "navigation directe -> diagnostic" }
                    controleur.navigate(R.id.diagnostics)
                }
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
            when (navControllerDHote?.currentDestination?.id) {
                R.id.home -> {
                    logger.d(TAG) { "navigation accueil -> installation des outils" }
                    navControllerDHote?.navigate(R.id.action_home_to_installation)
                }

                R.id.onboarding -> {
                    logger.d(TAG) { "navigation assistant -> installation des outils" }
                    navControllerDHote?.navigate(R.id.action_onboarding_to_installation)
                }

                null -> {
                    // Depuis la carte Terminal de l'éditeur (v0.31.4) :
                    // relance de l'hôte du graphe avec routage.
                    relancerMainActivity(RoutageEcran.ECRAN_INSTALLATION)
                }

                else -> {
                    logger.d(TAG) { "navigation directe -> installation des outils" }
                    navControllerDHote?.navigate(R.id.installation)
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

        /**
         * Navigation vers un écran du graphe : direct quand l'hôte porte
         * le graphe, relance routée de `MainActivity` sinon (activité
         * plein écran appelante).
         */
        private fun naviguerDansLeGraphe(
            actionDepuisAccueil: Int,
            destination: Int,
            ecran: String,
            journal: String,
        ) {
            val controleur = navControllerDHote
            if (controleur != null) {
                // Garde-fou : naviguer deux fois d'affilée (double
                // toucher) ferait lever deux fragments ; on ne navigue
                // que depuis l'accueil, sinon destination directe.
                if (controleur.currentDestination?.id == R.id.home) {
                    logger.d(TAG) { "navigation accueil -> $journal" }
                    controleur.navigate(actionDepuisAccueil)
                } else if (controleur.currentDestination?.id != destination) {
                    logger.d(TAG) { "navigation directe -> $journal" }
                    controleur.navigate(destination)
                }
                return
            }
            relancerMainActivity(ecran)
        }

        /**
         * Remonte l'instance existante de `MainActivity` (jamais
         * recréée, jamais doublée) avec un routage d'écran : l'activité
         * appelante reste en dessous, un retour système y revient.
         */
        private fun relancerMainActivity(ecran: String) {
            logger.d(TAG) { "routage via l'accueil hôte -> $ecran" }
            val intention =
                Intent(activity, MainActivity::class.java)
                    .putExtra(RoutageEcran.EXTRA_ECRAN_CIBLE, ecran)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
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

/** Clé de la section « bientôt disponible » (ADR 0059). */
private const val CLE_SECTION_BIENTOT = "section"
