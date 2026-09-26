package jo.codeide.core.data

import android.content.Context
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.ThemeMode

/**
 * Miroir synchrone du réglage d'apparence (ADR 0059) : deux clés — mode
 * de thème et couleurs dynamiques — dupliquées de DataStore vers des
 * `SharedPreferences` dédiées, **lisibles sans coroutine avant toute
 * activité**.
 *
 * Pourquoi un miroir : DataStore est asynchrone par conception, or
 * `CodeIdeApplication.onCreate()` doit décider **synchrone** (et dans le
 * processus `:crash`, sans Hilt ni DataStore) s'il installe le callback
 * Material You et quel mode de nuit appliquer. Le réglage reste
 * Source-de-vérité dans DataStore ; ce fichier n'est qu'une **cache
 * technique**, réécrite à chaque persistance réussie
 * ([SettingsRepositoryImpl]) — jamais lue pour autre chose que le
 * démarrage à froid des processus.
 *
 * Lecture défensive : fichier absent (première installation, montée de
 * version depuis v0.33.x) → valeurs par défaut de [AppSettings] ; la
 * première écriture re-converge le miroir.
 */
public object MiroirApparence {
    /** Fichier dédié — jamais mélangé aux préférences DataStore. */
    private const val FICHIER = "apparence_miroir"

    /** Clé du mode de thème (nom du [ThemeMode]). */
    private const val CLE_MODE_THEME = "mode_theme"

    /** Clé des couleurs dynamiques (booléen). */
    private const val CLE_COULEURS_DYNAMIQUES = "couleurs_dynamiques"

    /** Apparence lue en miroir — tuple minimal, rien de plus. */
    public data class EtatApparence(
        public val modeTheme: ThemeMode,
        public val couleursDynamiques: Boolean,
    )

    /**
     * Lit le miroir synchrone — appelé par `CodeIdeApplication.onCreate()`
     * dans **chaque** processus, avant Hilt.
     *
     * @param context contexte applicatif (lecture seule).
     * @return l'état miroir, ou les défauts si le fichier est vide.
     */
    public fun lire(context: Context): EtatApparence {
        val preferences = context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
        val mode =
            ThemeMode.entries.firstOrNull { it.name == preferences.getString(CLE_MODE_THEME, null) }
                ?: ThemeMode.SYSTEM
        val dynamiques = preferences.getBoolean(CLE_COULEURS_DYNAMIQUES, true)
        return EtatApparence(modeTheme = mode, couleursDynamiques = dynamiques)
    }

    /**
     * Réécrit le miroir après une persistance DataStore réussie —
     * `apply()` (asynchrone disque, synchrone mémoire) suffit : la
     * prochaine lecture, au prochain démarrage de processus, retombera
     * sur un fichier déjà visible.
     *
     * @param context contexte applicatif.
     * @param reglages état persisté source de la recopie.
     */
    public fun ecrire(
        context: Context,
        reglages: AppSettings,
    ) {
        context
            .getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
            .edit()
            .putString(CLE_MODE_THEME, reglages.themeMode.name)
            .putBoolean(CLE_COULEURS_DYNAMIQUES, reglages.useDynamicColor)
            .apply()
    }
}
