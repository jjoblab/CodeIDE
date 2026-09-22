package jo.codeide.core.crash

import android.app.Application
import android.os.Bundle

/**
 * Dernier écran connu avant un plantage (section 5.8) : le nom de l'activité
 * résidente, affiné par la destination de navigation dès que `app` en
 * informe le gestionnaire.
 *
 * Le pisteur s'enregistre lui-même comme écouteur du cycle de vie à
 * l'installation du gestionnaire : aucune configuration supplémentaire.
 * La valeur est volatil — lue depuis n'importe quel thread au moment du
 * plantage, sans verrou.
 */
internal class LastScreenTracker : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var dernierEcran: String? = null

    /** Dernier écran connu, ou `null` avant tout affichage. */
    fun current(): String? = dernierEcran

    /**
     * Destination de navigation affichée — appelée par l'écouteur de
     * destination de `app` (libellé du graphe, plus précis que l'activité).
     *
     * @param ecran libellé de la destination.
     */
    fun onNavigatedTo(ecran: String) {
        dernierEcran = ecran
    }

    override fun onActivityResumed(activity: android.app.Activity) {
        dernierEcran = activity.javaClass.simpleName
    }

    override fun onActivityCreated(
        activity: android.app.Activity,
        savedInstanceState: Bundle?,
    ) {
        // Seul le résumé compte : géré par onActivityResumed.
    }

    override fun onActivityPaused(activity: android.app.Activity) {
        // L'écran reste « le dernier connu » — c'est le but.
    }

    override fun onActivityStarted(activity: android.app.Activity) {
        // Seul le résumé compte : géré par onActivityResumed.
    }

    override fun onActivityStopped(activity: android.app.Activity) {
        // L'écran reste « le dernier connu » — c'est le but.
    }

    override fun onActivitySaveInstanceState(
        activity: android.app.Activity,
        outState: Bundle,
    ) {
        // Sans objet pour un pisteur mémoire.
    }

    override fun onActivityDestroyed(activity: android.app.Activity) {
        // L'écran reste « le dernier connu » — c'est le but.
    }
}
