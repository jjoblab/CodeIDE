package jo.codeide.core.crash

import android.app.Application
import android.content.Context
import android.os.Build

/**
 * Processus dans lequel s'exécute ce lancement de l'application
 * (section 5.8 : l'`Application` est sensible au processus).
 *
 * `CrashActivity` vit dans un processus `:crash` dédié : son démarrage
 * réexécute `Application.onCreate`, qui doit alors s'arrêter sur une
 * initialisation **minimale** — pas de `FileSink`, pas de Room, pas de
 * DataStore, gestionnaire de plantage en mode sûr.
 *
 * La détection suit le prompt : `Application.getProcessName()` à partir de
 * l'API 28, lecture de `/proc/self/cmdline` avant.
 */
public enum class AppProcess {
    /** Processus principal : initialisation complète. */
    MAIN,

    /** Processus dédié de l'écran de plantage : initialisation minimale. */
    CRASH,

    /** Tout autre processus (ex. outils de diagnostic externes). */
    OTHER,

    ;

    public companion object {
        /** Suffixe du processus dédié à l'écran de plantage. */
        public const val CRASH_PROCESS_SUFFIX: String = ":crash"

        /**
         * Détecte le processus courant.
         *
         * @param context contexte applicatif (nom du paquet = référence du
         * processus principal).
         * @return le processus identifié, [OTHER] si le nom ne correspond à
         * aucun cas connu.
         */
        @JvmStatic
        public fun detect(context: Context): AppProcess {
            val nom = nomCourant()
            return when {
                nom == context.packageName -> MAIN
                nom.endsWith(CRASH_PROCESS_SUFFIX) -> CRASH
                else -> OTHER
            }
        }

        /** Nom du processus courant, par l'API système puis par /proc. */
        private fun nomCourant(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                lireProcCmdline()
            }

        /**
         * Lecture de `/proc/self/cmdline` (API 26-27) : lisible sans
         * permission, contenu tronqué au premier octet nul.
         */
        private fun lireProcCmdline(): String =
            try {
                java.io
                    .File("/proc/self/cmdline")
                    .readBytes()
                    .takeWhile { octet -> octet != 0.toByte() }
                    .toByteArray()
                    .decodeToString()
            } catch (erreur: Exception) {
                // Chemin de repli : un nom vide ne correspond ni au paquet ni
                // au suffixe :crash — la détection rendra OTHER et le
                // gestionnaire saura déléguer de toute façon.
                ""
            }
    }
}
