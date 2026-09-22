package jo.codeide.core.domain

import java.security.MessageDigest

/**
 * Expurgation des textes destinés aux journaux et aux rapports (règle 15 du
 * prompt maître : **aucune donnée personnelle** dans les journaux).
 *
 * Trois familles de données sont masquées :
 * - les adresses e-mail → `<courriel>` ;
 * - les URI `content://` : l'**autorité est conservée** (utile au diagnostic,
 *   elle identifie le fournisseur SAF) et l'identifiant du document est
 *   **haché** sur 8 caractères hexadécimaux — deux URI identiques produisent
 *   le même hachage (corrélables), deux URI différentes restent
 *   indistinguablement masquées ;
 * - les chemins absolus et les URI `file://` → `<chemin>`.
 *
 * L'expurgation est appliquée **à l'écriture** (pipeline de journalisation),
 * pas seulement à l'export : ce qui est sur disque est déjà propre. Elle est
 * idempotente — réexpurger un texte déjà masqué ne le déforme pas (le
 * hachage d'une URI déjà hachée est reconnu et conservé tel quel).
 *
 * Limites assumées et documentées : un chemin contenant des espaces n'est
 * masqué que jusqu'au premier espace ; les URI http(s) ne sont **pas**
 * masquées (elles ne portent aucune donnée locale). Fonctions pures, sans
 * état : réutilisées telles quelles par `core:crash` (étape 3).
 */
public object LogRedactor {
    private const val MASK_COURRIEL = "<courriel>"
    private const val MASK_CHEMIN = "<chemin>"
    private const val PREFIXE_HACHE = "/h-"

    /** Nombre de caractères hexadécimaux de l'identifiant haché. */
    private const val TAILLE_HACHAGE = 8

    private val regexCourriel = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /** Autorité = premier segment ; reste = identifiant du document. */
    private val regexUriContenu = Regex("""\bcontent://([^/\s"'()<>]+)([^\s"'()<>]*)""")

    private val regexUriFichier = Regex("""\bfile://[^\s"'()<>]+""")

    /**
     * Chemin absolu : un `/` initial non précédé d'un caractère de mot, de
     * `:` ou de `/` (ce qui épargne les URI http(s) déjà formées), suivi de
     * segments.
     */
    private val regexCheminAbsolu = Regex("""(?<![\w:/])/[\w.@+-]+(?:/[\w.@+-]+)*/?""")

    /** Trace d'une URI `content://` déjà expurgée (idempotence). */
    private val regexHachage = Regex("""/h-[0-9a-f]{$TAILLE_HACHAGE}""")

    /**
     * Expurge un texte en une passe.
     *
     * @param text texte brut produit par le code appelant (message de
     * journal, message d'exception…).
     * @return le texte masqué, prêt à être persisté ou affiché.
     */
    public fun redact(text: String): String =
        text
            .replace(regexCourriel, MASK_COURRIEL)
            .replace(regexUriContenu) { correspondance -> epurerUriContenu(correspondance) }
            .replace(regexUriFichier) { "file://$MASK_CHEMIN" }
            .replace(regexCheminAbsolu, MASK_CHEMIN)

    /**
     * Remplace une URI `content://` par sa forme autorité + identifiant
     * haché, sauf si elle est déjà expurgée.
     */
    private fun epurerUriContenu(correspondance: MatchResult): String {
        val autorite = correspondance.groupValues[1]
        val reste = correspondance.groupValues[2]
        return when {
            reste.isEmpty() -> correspondance.value
            regexHachage.matches(reste) -> correspondance.value
            else -> "content://$autorite$PREFIXE_HACHE${hacher(reste)}"
        }
    }

    /**
     * Hache un identifiant en SHA-256 tronqué, hexadécimal — déterministe
     * (même entrée, même sortie) mais non réversible de fait.
     */
    private fun hacher(identifiant: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(identifiant.toByteArray(Charsets.UTF_8))
            .take(TAILLE_HACHAGE / 2)
            .joinToString(separator = "") { octet -> "%02x".format(octet) }
}
