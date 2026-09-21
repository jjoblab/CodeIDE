package jo.codeide

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp

/**
 * Application CodeIDE — point d'assemblage de Hilt (section 5.1).
 *
 * Responsabilités de l'étape 1 :
 * - porter l'annotation [HiltAndroidApp] qui génère le composant racine ;
 * - activer StrictMode **uniquement en debug** (règle 5 du prompt :
 *   détection des I/O sur le thread principal et des fuites mémoire) ;
 * - LeakCanary s'installe tout seul via sa dépendance `debugImplementation`.
 *
 * La journalisation (`core:logging`, étape 2) et la gestion des plantages
 * (`core:crash`, étape 3) viendront s'installer ici, la capture en toute
 * première ligne d'`onCreate` le moment venu.
 *
 * NB : le mode debug est détecté via `FLAG_DEBUGGABLE` plutôt que
 * `BuildConfig.DEBUG` — pas besoin d'activer buildConfig pour ça, et le
 * comportement suit la variante réellement installée.
 */
@HiltAndroidApp
class CodeIdeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (estDebuggable()) {
            activerStrictMode()
        }
    }

    /**
     * Indique si l'application est installée en variante debug.
     *
     * @return `true` si le drapeau `FLAG_DEBUGGABLE` est posé sur le paquet.
     */
    private fun estDebuggable(): Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Active StrictMode avec journalisation (jamais de crash en debug pour
     * une violation : les rapports partent dans le logcat et sont relevés
     * lors des recettes).
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
}
