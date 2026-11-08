package jo.codeide.feature.editor

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signaux système consolidés (Phase 1, ADR 0152 — le fait F6 du journal de
 * terrain : « caches d'arbre libérés (pression mémoire) », un signal nu
 * SANS CONTEXTE) : chaque `onTrimMemory` significatif est accompagné de la
 * mémoire disponible de l'app (`ActivityManager.MemoryInfo`) et du système
 * (`/proc/meminfo` : MemAvailable, Cached) — le diagnostic X4 (pression
 * mémoire) lira ENFIN l'état mémoire au moment du signal, pas seulement
 * le signal.
 *
 * La lecture est ASYNCHRONE (règle 5 : I/O hors du fil principal — le
 * rappel `onTrimMemory` arrive sur le fil principal, `/proc/meminfo` est
 * une lecture de fichier) sur une portée interne supervisée : un diagnostic
 * ne peut ni geler l'UI ni planter l'app (échec de lecture = ligne sans la
 * partie système, jamais une exception).
 *
 * Étiquette dédiée `PressionMemoire` : la sortie passe par le journal
 * applicatif (règle 10), donc par l'export de logs du propriétaire.
 */
@Singleton
class SondesSysteme
@Inject
constructor(
    // Annotation sans `private val` (leçon T1) : évite le warning K2
    // « appliquée au paramètre seulement » — champ dérivé ci-dessous.
    @ApplicationContext contexte: Context,
    private val journal: AppLogger,
    dispatchers: DispatcherProvider,
) {
    private val contexteApplication: Context = contexte.applicationContext

    /** Portée interne supervisée (un diagnostic ne tue jamais l'app). */
    private val portee = CoroutineScope(SupervisorJob() + dispatchers.io)

    /**
     * Consigne un signal de pression mémoire avec son CONTEXTE — appelé
     * par l'activité sur `onTrimMemory(niveau >= RUNNING_CRITICAL)` : le
     * niveau brut, la mémoire de l'app (utilisée / seuil / drapeau basse
     * mémoire) et la mémoire système (MemAvailable / Cached) partent au
     * journal applicatif sur le fil IO.
     *
     * Règle 15 : identifiants et chiffres seulement, jamais de contenu
     * utilisateur.
     */
    fun signalerTrimMemory(niveau: Int) {
        portee.launch {
            val ligne = lireEtatMemoire(niveau)
            journal.i(TAG) { ligne }
        }
    }

    /** Lit l'état mémoire (app + système) — tolère tout échec de lecture. */
    private fun lireEtatMemoire(niveau: Int): String {
        val gestionnaire = contexteApplication.getSystemService(ActivityManager::class.java)
        val memoire = ActivityManager.MemoryInfo()
        val app =
            if (gestionnaire != null && runCatching { gestionnaire.getMemoryInfo(memoire) }.isSuccess) {
                val basse = if (memoire.lowMemory) ", BASSE" else ""
                "availMem ${memoire.availMem / MIO} Mo (seuil ${memoire.threshold / MIO} Mo$basse)"
            } else {
                "availMem n/a"
            }
        return "pression mémoire (niveau $niveau) — $app ; ${lireMeminfo()}"
    }

    /** Extrait MemAvailable et Cached de `/proc/meminfo` (repli « n/a »). */
    private fun lireMeminfo(): String = runCatching {
        val valeurs =
            File("/proc/meminfo")
                .readLines()
                .filter { it.startsWith("MemAvailable:") || it.startsWith("Cached:") }
                .associate { ligne ->
                    val morceaux = ligne.split(Regex("\\s+"))
                    morceaux.first().removeSuffix(":") to morceaux.getOrNull(1)?.toLongOrNull()
                }
        val disponible = valeurs["MemAvailable"]
        val cache = valeurs["Cached"]
        if (disponible == null) {
            "système n/a"
        } else {
            val cacheTexte = cache?.let { ", cache ${it / KIO} Mo" } ?: ""
            "système ${disponible / KIO} Mo disponibles$cacheTexte"
        }
    }.getOrDefault("système n/a")

    private companion object {
        /** Étiquette dédiée des signaux système (règle 10 : tags dédiés). */
        const val TAG = "PressionMemoire"

        /** Conversions pour l'affichage (kilo/méga_octets de /proc). */
        const val KIO = 1_024L
        const val MIO = 1_024L * 1_024L
    }
}
