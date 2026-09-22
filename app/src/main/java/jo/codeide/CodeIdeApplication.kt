package jo.codeide

import android.app.Application
import android.os.Build
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp
import jo.codeide.core.logging.LoggingInitializer
import javax.inject.Inject

/**
 * Application CodeIDE — point d'assemblage de Hilt (section 5.1).
 *
 * Responsabilités :
 * - porter l'annotation [HiltAndroidApp] qui génère le composant racine ;
 * - activer StrictMode **uniquement en debug** (détection des I/O sur le
 *   thread principal et des fuites mémoire) ;
 * - initialiser la journalisation maison dans le **processus principal
 *   uniquement** (section 5.7 : le `FileSink` n'écrit jamais depuis un autre
 *   processus, anti-corruption) — la capture de plantages (étape 3) viendra
 *   s'installer en toute première ligne le moment venu ;
 * - LeakCanary s'installe tout seul via sa dépendance `debugImplementation`.
 */
@HiltAndroidApp
class CodeIdeApplication : Application() {
    @Inject
    lateinit var loggingInitializer: LoggingInitializer

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            activerStrictMode()
        }

        if (estProcessusPrincipal()) {
            loggingInitializer.initialize()
        }
    }

    /**
     * Indique si ce lancement est le processus principal de l'application.
     *
     * Un processus `:crash` (étape 3) ne doit jamais écrire dans les fichiers
     * de journal : seule la nomination du processus est examinée ici, la
     * décision d'écrire revient de toute façon à la configuration du sink.
     *
     * @return `true` si le processus porte le nom du paquet (processus
     * principal).
     */
    private fun estProcessusPrincipal(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName() == packageName
        } else {
            // API 26-27 : lecture de /proc — lisible sans permission et
            // invisible pour StrictMode (procfs, pas d'I/O disque).
            nomProcessusCompat() == packageName
        }

    /** Nom du processus par /proc/self/cmdline (API 26-27). */
    private fun nomProcessusCompat(): String =
        java.io
            .File("/proc/self/cmdline")
            .readBytes()
            .takeWhile { octet -> octet != 0.toByte() }
            .toByteArray()
            .decodeToString()

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
}
