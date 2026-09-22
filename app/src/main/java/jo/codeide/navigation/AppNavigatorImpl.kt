package jo.codeide.navigation

import android.app.Activity
import android.view.View
import androidx.navigation.NavController
import androidx.navigation.findNavController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import jo.codeide.R
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.ui.AppNavigator
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
