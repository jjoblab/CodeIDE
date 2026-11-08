package jo.codeide.tooling.client

/**
 * Sonde du fil lecteur de la session — **T3 détaillé** de la Phase 1 du
 * diagnostic « retard d'affichage des sorties de build » (ADR 0152,
 * `DIAGNOSTIC-LOGS.md` §3 : « durées read/décodage/router par frame au
 * fil lecteur »).
 *
 * La session réelle ([SessionSocketAndroid]) lit son socket dans UN fil
 * dédié hors dispatcheurs (ADR 0081) : la sonde y est appelée AU POINT DE
 * LECTURE, frame par frame, avec la durée de chacune des trois phases du
 * fil —
 *
 * ```
 * lecture (readFrame : en-tête + payload) ─► décodage JSON ─► routage (chemin rapide)
 * ```
 *
 * — ce qui complète la latence de TRANSPORT existante (réception −
 * émission, mesurée dans le routeur) : un transport lent aux phases
 * internes multiples se distingue désormais d'un transport lent à
 * l'arrivée seule.
 *
 * **Désactivable** (règle 10 du prompt) : `null` à la construction (aucune
 * mesure, aucun appel — le chemin du fil est l'identique d'avant) ; une
 * sonde fournie reste maîtresse de son propre drapeau d'activation.
 *
 * Thread-safe par contrat : [frame] est appelée par le fil lecteur,
 * [prendreResume] par le consommateur (pompe cliente) — implementations
 * sans verrou, dans l'esprit d'`AccumulateurLatence`.
 */
interface SondeLectureSocket {
    /**
     * Une frame vient d'être traitée par le fil lecteur.
     *
     * @param dureeLectureNs durée de `FrameCodec.readFrame` (en-tête +
     *        payload), en nanosecondes.
     * @param dureeDecodageNs durée du décodage JSON de l'événement, en
     *        nanosecondes.
     * @param dureeRoutageNs durée du chemin rapide de routage, en
     *        nanosecondes.
     */
    fun frame(dureeLectureNs: Long, dureeDecodageNs: Long, dureeRoutageNs: Long)

    /**
     * Prend le résumé accumulé depuis le dernier appel — un instantané
     * LISIBLE pour le journal applicatif (identifiants et durées
     * seulement, règle 15), ou `null` si aucune frame n'a été observée
     * depuis : ne rien journaliser vaut mieux qu'une ligne vide.
     *
     * « Prendre » = consommer : les accumulateurs repartent à zéro, le
     * résumé suivant ne déborde pas du précédent.
     */
    fun prendreResume(): String?
}
