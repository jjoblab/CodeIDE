package jo.codeide.core.domain

import java.util.concurrent.atomic.AtomicLong

/**
 * Accumulateur de latence d'un canal de sortie (v0.43.0 — mesure de la
 * console lente) : agrège les écarts « horodatage d'émission → instant
 * mesuré » observés ligne à ligne pendant un build.
 *
 * Le protocole horodate déjà chaque ligne serveur
 * (`BuildOutput.timestampMs`, pris par `StreamingOutputStream` au moment
 * de la découpe en lignes) : il suffit de mesurer l'instant courant aux
 * étapes de la chaîne cliente et d'en soustraire l'horodatage pour
 * localiser le goulot —
 *
 * ```
 * émission serveur ──► réception client ──► publication zone texte ──► rendu
 *   (timestampMs)        (GradleApiImpl)         (GradleService)        (vue)
 * ```
 *
 * Deux accumulateurs par build (transport dans `tooling:client`,
 * publication dans `feature:editor`) : la différence de leurs maxima
 * mesure la moitié cliente (canaux, pompe), le maximum de transport
 * mesure la moitié serveur+socket (file de l'EventBus, contre-pression).
 *
 * Thread-safe sans verrou (appelé depuis le pompe unique du client et le
 * vidange process-wide) : compteurs atomiques, pas d'instantané
 * intermédiaire — le résumé n'est lu qu'une fois, à la fin du build.
 *
 * @param seuilAlerteMs écart au-delà duquel le résumé signale une latence
 *        anormale (2 s par défaut : au-delà, l'utilisateur perçoit le
 *        retard à l'affichage).
 */
public class AccumulateurLatence(
    private val seuilAlerteMs: Long = SEUIL_ALERTE_MS,
) {
    private val compteur = AtomicLong(0)
    private val total = AtomicLong(0)
    private val minimum = AtomicLong(Long.MAX_VALUE)
    private val maximum = AtomicLong(Long.MIN_VALUE)

    /** Enregistre un écart observé (ms — négatif si horloges désynchronisées). */
    public fun enregistrer(ecartMs: Long) {
        compteur.incrementAndGet()
        total.addAndGet(ecartMs)
        minimum.accumulateAndGet(ecartMs) { courant, nouveau -> minOf(courant, nouveau) }
        maximum.accumulateAndGet(ecartMs) { courant, nouveau -> maxOf(courant, nouveau) }
    }

    /** Nombre d'écarts enregistrés. */
    public val nombre: Long
        get() = compteur.get()

    /** Résumé lisible pour le journal (identifiants seulement, règle 15). */
    public fun description(): String {
        val n = compteur.get()
        if (n == 0L) return "0 ligne"
        val min = minimum.get().coerceAtLeast(0)
        val max = maximum.get().coerceAtLeast(0)
        val moyen = (total.get() / n).coerceAtLeast(0)
        val alerte = if (max > seuilAlerteMs) " — LATENCE ÉLEVÉE" else ""
        return "$n ligne(s), écart min $min ms / moyen $moyen ms / max $max ms$alerte"
    }

    private companion object {
        /** Écart au-delà duquel la latence est signalée comme anormale. */
        private const val SEUIL_ALERTE_MS: Long = 2_000L
    }
}
