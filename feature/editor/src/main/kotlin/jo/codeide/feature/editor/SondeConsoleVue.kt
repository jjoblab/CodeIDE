package jo.codeide.feature.editor

import jo.codeide.core.domain.AppLogger

/**
 * Sonde de la VUE console — **T6** de la Phase 1 (ADR 0152,
 * `DIAGNOSTIC-LOGS.md` §3 : « images perdues, layout/dessin »).
 *
 * Elle mesure la cadence d'images du fil principal pendant que la vue
 * est attachée. (L5b : la mesure T5 du vidage loti s'est ÉTEINTE avec le
 * moteur qu'elle chronométrait — les deux consoles `TextView` sont
 * remplacées par le lecteur du Centre de sorties, qui a ses propres
 * budgets et ses propres tests ; le déluge H-V1…V5 (X9) se lira sur les
 * compteurs du lecteur.)
 *
 * **Désactivable** (règle 10) : [active] est piloté par le réglage
 * utilisateur `instrumentationTransport` (observé par le fragment) —
 * coupée, chaque méthode rend la main immédiatement, aucun compteur, la
 * vue ne paie rien.
 *
 * Les lignes portent le préfixe `instru:` (même famille que les lignes du
 * serveur, `instru: build …`) sous l'étiquette dédiée `ConsoleVue` : la
 * sortie d'instrumentation passe par le journal applicatif (règle 10) et
 * reste dans l'export de logs du propriétaire.
 *
 * Thread : [image] est appelée sur le fil principal (rappel
 * Choreographer) — aucun verrou nécessaire.
 */
internal class SondeConsoleVue(private val journal: AppLogger) {
    /** Drapeau d'activation — piloté par le réglage utilisateur. */
    @Volatile
    var active: Boolean = false

    // ---- T6 : les images du fil principal ------------------------------

    /** Images rendues depuis le dernier résumé. */
    private var imagesRendues = 0L

    /** Images perdues estimées depuis le dernier résumé (approx. 60 Hz). */
    private var imagesPerdues = 0L

    /** Pire intervalle entre deux images depuis le dernier résumé (ms). */
    private var pireIntervalleMs = 0L

    /** Instant de la précédente image (ms d'uptime Choreographer). */
    private var precedenteImageMs = 0L

    /** Instant du dernier résumé émis (ms d'uptime). */
    private var dernierResumeMs = 0L

    /**
     * Consigne UNE image rendue (T6) — appelée par le rappel
     * `Choreographer` du fragment, avec l'uptime Choreographer en ms.
     *
     * Images perdues : un intervalle au-delà d'une image (≈ 33 ms à
     * 60 Hz nominal) compte ses images manquantes (`intervalle / période
     * − 1`) ; le pire intervalle est suivi pour TOUTES les images (il
     * dit le gel, cible « aucun gel > 100 ms du fil principal »). Un
     * résumé part tous les [INTERVALLE_RESUME_MS] quand des images ont
     * été rendues — borné, jamais un déluge de lignes.
     */
    fun image(instantMs: Long) {
        if (!active) {
            precedenteImageMs = instantMs
            return
        }
        if (precedenteImageMs != 0L && instantMs > precedenteImageMs) {
            val intervalMs = instantMs - precedenteImageMs
            imagesRendues++
            if (pireIntervalleMs < intervalMs) pireIntervalleMs = intervalMs
            if (intervalMs > SEUIL_IMAGE_PERDUE_MS) {
                imagesPerdues += (intervalMs / PERIODE_IMAGE_NOMINALE_MS) - 1
            }
        }
        precedenteImageMs = instantMs
        if (imagesRendues > 0 && instantMs - dernierResumeMs >= INTERVALLE_RESUME_MS) {
            journal.i(TAG) {
                "instru: images — $imagesRendues rendue(s), ~$imagesPerdues perdue(s), " +
                    "pire interval $pireIntervalleMs ms (vue console attachée)"
            }
            imagesRendues = 0
            imagesPerdues = 0
            pireIntervalleMs = 0
            dernierResumeMs = instantMs
        }
    }

    private companion object {
        /** Étiquette dédiée de l'instrumentation de la vue (règle 10). */
        const val TAG = "ConsoleVue"

        /** Période d'image nominale (60 Hz) — l'estimation est dite approximative. */
        const val PERIODE_IMAGE_NOMINALE_MS = 17L

        /** Deux périodes sans image = au moins une image perdue. */
        const val SEUIL_IMAGE_PERDUE_MS = 33L

        /** Un résumé d'images par tranche de 5 s (borné). */
        const val INTERVALLE_RESUME_MS = 5_000L
    }
}
