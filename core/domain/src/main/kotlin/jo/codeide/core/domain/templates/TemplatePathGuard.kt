package jo.codeide.core.domain.templates

/**
 * Garde de sécurité des chemins générés (étape 8 — section 11 : rejeter
 * `..`, chemins absolus, séparateurs invalides, doublons, noms réservés ;
 * ne jamais écraser un fichier existant).
 *
 * S'applique **après** substitution des chemins templatisables : le contenu
 * d'une variable ne peut jamais s'échapper du dossier du projet généré.
 * Les projets pouvant être copiés vers un PC (section 12.3), les règles de
 * portabilité Windows (noms réservés, longueurs) sont aussi vérifiées.
 */
internal object TemplatePathGuard {
    /** Longueur maximale du chemin relatif complet. */
    internal const val LONGUEUR_CHEMIN_MAX = 240

    /** Longueur maximale d'un segment (limite conservative FAT/NTFS). */
    internal const val LONGUEUR_SEGMENT_MAX = 120

    /** Dernier numéro de série historique (COM1-9, LPT1-9). */
    private const val DERNIER_NUMERO_SERIE = 9

    /** Numéros de série historiques (COM1-9, LPT1-9). */
    private val NUMEROS_SERIE_WINDOWS = 1..DERNIER_NUMERO_SERIE

    /** Point de sortie Unicode des caractères de contrôle. */
    private const val POINT_CONTROLE = 0x20

    /** Noms réservés Windows — comparés sur la base sans extension. */
    private val NOMS_RESERVES_WINDOWS =
        setOf("CON", "PRN", "AUX", "NUL") +
            NUMEROS_SERIE_WINDOWS.map { "COM$it" } +
            NUMEROS_SERIE_WINDOWS.map { "LPT$it" }

    /**
     * Valide un chemin relatif rendu.
     *
     * @return `null` si le chemin est sûr, sinon le message d'erreur français.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Une branche par danger de chemin (sécurité).
    fun valider(chemin: String): String? {
        if (chemin.isBlank()) return "chemin vide"
        if (chemin.length > LONGUEUR_CHEMIN_MAX) {
            return "chemin trop long (${chemin.length} > $LONGUEUR_CHEMIN_MAX caractères) : « $chemin »"
        }
        if (chemin.startsWith("/")) return "chemin absolu interdit : « $chemin »"
        if (chemin.contains('\\')) return "séparateur « \\ » interdit (utiliser « / ») : « $chemin »"
        if (Regex("^[A-Za-z]:").containsMatchIn(chemin)) {
            return "chemin absolu (lettre de lecteur) interdit : « $chemin »"
        }
        val segments = chemin.split("/")
        for (segment in segments) {
            segment.let {
                if (it.isEmpty()) return "segment vide dans le chemin : « $chemin »"
                if (it == "." || it == "..") return "segment « $it » interdit : « $chemin »"
                if (it.length > LONGUEUR_SEGMENT_MAX) {
                    return "segment trop long (${it.length} > $LONGUEUR_SEGMENT_MAX) dans : « $chemin »"
                }
                val fautif = it.firstOrNull { caractere -> caractere.code < POINT_CONTROLE }
                if (fautif != null) {
                    return "caractère de contrôle dans le segment « $it » du chemin « $chemin »"
                }
                if (it != it.trim()) return "espaces de bord interdits dans le segment « $it »"
                if (it.endsWith(".")) return "segment « $it » : point final interdit (portabilité Windows)"
                if (nomReserve(it)) return "segment « $it » : nom réservé par Windows"
            }
        }
        return null
    }

    /** Nom réservé Windows ? (avec ou sans extension : `CON`, `CON.txt`…). */
    private fun nomReserve(segment: String): Boolean {
        val base = segment.substringBefore(".").uppercase()
        return base in NOMS_RESERVES_WINDOWS
    }

    /**
     * Vérifie l'unicité des chemins d'un plan — deux entrées ne peuvent pas
     * cibler le même fichier (jamais d'écrasement).
     *
     * @return le message d'erreur du premier doublon, ou `null`.
     */
    fun verifierDoublons(chemins: List<String>): String? {
        val vus = mutableSetOf<String>()
        for (chemin in chemins) {
            // Comparaison insensible à la casse : sur FAT/NTFS (copie vers un
            // PC), « Readme.md » et « README.md » entreraient en collision.
            val cle = chemin.lowercase()
            if (!vus.add(cle)) {
                return "chemin en double dans le plan : « $chemin »"
            }
        }
        return null
    }
}
