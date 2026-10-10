package jo.codeide.core.domain

/**
 * Analyseur défensif des trames du pont de journaux (mission « Exécuter »,
 * ADR 0103 — « tabulaire à message-reste »).
 *
 * Le contenu reçu est **non fiable** : une trame peut être tronquée,
 * corrompue ou hostile. L'analyseur ne lève JAMAIS d'exception — chaque
 * trame non conforme rend `null` et est ignorée (le pont sain produit
 * 100 % de trames conformes ; la défense est pour le reste).
 *
 * Format accepté (cf. `Trames.java` côté pont) :
 * - `L \t niveau \t epoch_ms \t pid \t tid \t étiquette \t message…`
 *   (le message est le reste : il peut contenir des tabulations) ;
 * - `X \t codeRaison \t description…` (fin du processus précédent).
 */
public object AnalyseurTramesJournal {
    /** Taille maximale d'une trame acceptée (le pont tronque à ~4 ko). */
    private const val TAILLE_TRAME_MAX = 16_000

    /** Nombre maximal de champs avant le message-reste. */
    private val NIVEAUX: Map<Char, NiveauJournal> =
        mapOf(
            'V' to NiveauJournal.VERBEUX,
            'D' to NiveauJournal.DEBOGAGE,
            'I' to NiveauJournal.INFO,
            'W' to NiveauJournal.AVERTISSEMENT,
            'E' to NiveauJournal.ERREUR,
            'F' to NiveauJournal.ASSERT,
        )

    /** Une trame analysée : ligne, ou information de fin précédente. */
    public sealed interface TrameAnalysee {
        /** Ligne de journal. */
        public data class Ligne(
            val ligne: LigneJournal,
        ) : TrameAnalysee

        /** Information de sortie du processus précédent (code + texte). */
        public data class SortiePrecedente(
            val codeRaison: Int,
            val description: String,
        ) : TrameAnalysee
    }

    /**
     * Analyse UNE trame — `null` si elle est non conforme (rejet silencieux,
     * la trame suivante n'en dépend pas).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque `return`
     * est un REJET de trame non conforme (le contrat même de l'analyseur
     * défensif : « jamais d'exception sur une donnée non fiable »).
     */
    @Suppress("ReturnCount")
    public fun analyser(trame: String): TrameAnalysee? {
        if (trame.length > TAILLE_TRAME_MAX) {
            return null
        }
        val type = trame.getOrNull(0) ?: return null
        if (trame.getOrNull(1) != '\t') {
            return null
        }
        return when (type) {
            'L' -> analyserLigne(trame)
            'X' -> analyserSortiePrecedente(trame)
            else -> null
        }
    }

    /**
     * `L \t niveau \t epoch \t pid \t tid \t étiquette \t message-reste`.
     * La coupure message-reste se fait après la SIXIÈME tabulation.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque `return`
     * rejette un champ invalide ; factoriser en exceptions trahirait le
     * contrat défensif.
     */
    @Suppress("ReturnCount")
    private fun analyserLigne(trame: String): TrameAnalysee.Ligne? {
        var debut = 2
        val niveauTexte = champ(trame, debut)?.takeIf { it.length == 1 } ?: return null
        val niveau = NIVEAUX[niveauTexte[0]] ?: return null
        debut += niveauTexte.length + 1
        val epochTexte = champ(trame, debut) ?: return null
        val epoch = epochTexte.toLongOrNull() ?: return null
        if (epoch < 0) {
            return null
        }
        debut += epochTexte.length + 1
        val pidTexte = champ(trame, debut) ?: return null
        val pid = pidTexte.toIntOrNull() ?: return null
        debut += pidTexte.length + 1
        val tidTexte = champ(trame, debut) ?: return null
        val tid = tidTexte.toIntOrNull() ?: return null
        debut += tidTexte.length + 1
        val etiquetteFin = champ(trame, debut) ?: return null
        val etiquette = trame.substring(debut, debut + etiquetteFin.length)
        val message =
            if (debut + etiquetteFin.length + 1 <= trame.length) {
                trame.substring(debut + etiquetteFin.length + 1)
            } else {
                ""
            }
        return TrameAnalysee.Ligne(
            LigneJournal(
                horodatageMs = epoch,
                pid = pid,
                tid = tid,
                niveau = niveau,
                etiquette = etiquette,
                message = message,
            ),
        )
    }

    /**
     * `X \t codeRaison \t description-reste`.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — rejets de champs
     * invalides, même contrat que [analyserLigne].
     */
    @Suppress("ReturnCount")
    private fun analyserSortiePrecedente(trame: String): TrameAnalysee.SortiePrecedente? {
        val codeTexte = champ(trame, 2) ?: return null
        val code = codeTexte.toIntOrNull() ?: return null
        val description =
            if (2 + codeTexte.length + 1 <= trame.length) {
                trame.substring(2 + codeTexte.length + 1)
            } else {
                ""
            }
        return TrameAnalysee.SortiePrecedente(code, description)
    }

    /**
     * Lit le champ à partir de [debut] jusqu'à la prochaine tabulation —
     * `null` si la trame s'arrête avant (champ manquant).
     */
    private fun champ(
        trame: String,
        debut: Int,
    ): String? {
        if (debut >= trame.length) {
            return null
        }
        val fin = trame.indexOf('\t', debut)
        return if (fin < 0) {
            trame.substring(debut)
        } else {
            trame.substring(debut, fin)
        }
    }
}
