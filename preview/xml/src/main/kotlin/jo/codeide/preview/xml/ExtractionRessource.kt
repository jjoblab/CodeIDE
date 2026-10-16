package jo.codeide.preview.xml

/**
 * EXTRACTION EN RESSOURCE (PE8c — le troisième « à prévoir » du prompt
 * PE7e § 3) — FONCTIONS PURES sur le TEXTE des deux documents : le
 * LITTÉRAL de la vue sélectionnée devient la référence `@string/nom`
 * ([extraire]) et l'entrée `<string name="nom">…</string>` rejoint le
 * fichier de valeurs ([entreeStrings]).
 *
 * Les DEUX documents sont édités SÉPARÉMENT par le ViewModel (chaque
 * session a son historique : deux gestes d'annulation — documenté) ; le
 * ViewModel écrit l'ENTRÉE D'ABORD pour ne jamais laisser une référence
 * orpheline dans le layout si l'entrée échoue.
 *
 * Périmètre : `@string` pour les libellés (`android:text`, `hint`,
 * `contentDescription`) ; le nom suit la CONVENTION minuscule
 * (`[a-z][a-z0-9_]*`, plus strict qu'aapt2 — lisible avant tout).
 *
 * Refus (philosophie X4 — `null`) : nom invalide, valeur vide ou déjà
 * référence, span périmé, fichier sans `</resources>`, nom DÉJÀ PRIS
 * dans le fichier de valeurs.
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde est
 * une issue DISTINCTE du refus (déjà référence ≠ nom invalide ≠ déjà
 * pris) ; les noyer masquerait la raison à l'utilisateur.
 */
@Suppress("ReturnCount")
public object ExtractionRessource {
    /** La convention de nommage des ressources (minuscule d'abord). */
    private val REGEX_NOM: Regex = Regex("[a-z][a-z0-9_]*")

    /** La longueur MAX d'un nom proposé (les noms longs ne se lisent pas). */
    private const val NOM_MAX: Int = 40

    /** Le nom de repli quand le littéral ne donne rien d'exploitable. */
    private const val NOM_REPLI: String = "texte"

    /** Le nom est-il acceptable ([a-z] d'abord, puis minuscules/chiffres/« _ ») ? */
    public fun nomValide(nom: String): Boolean = REGEX_NOM.matches(nom)

    /**
     * Le nom PROPOSÉ pour un littéral (le champ se pré-remplit — l'utilisateur
     * garde le dernier mot) : minuscules, tout non-alphanumérique devient
     * « _ », les répétitions se plient, les bords se dénudent, un chiffre
     * initial reçoit le préfixe « r », la longueur est plafonnée.
     */
    public fun nomPropose(valeur: String): String {
        val base =
            valeur
                .trim()
                .lowercase()
                .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
                .joinToString("")
                .replace(Regex("_+"), "_")
                .trim('_')
        val candidat =
            when {
                base.isEmpty() -> NOM_REPLI
                base.first().isDigit() -> "r$base"
                else -> base
            }
        return candidat.take(NOM_MAX).trimEnd('_').ifEmpty { NOM_REPLI }
    }

    /**
     * L'édition du LAYOUT : la valeur LITTÉRALE de [nomAttribut] sur la
     * vue au span devient `@string/[nomRessource]` (remplacement de la
     * valeur seule). `null` = refus (cf. KDoc de l'objet).
     */
    public fun extraire(
        texte: String,
        span: InsertionXml.SpanParent,
        nomAttribut: String,
        nomRessource: String,
    ): EditionAttributsXml.EditionAttributsXmlResultat? {
        val valeur = EditionAttributsXml.valeurAttribut(texte, span, nomAttribut) ?: return null
        if (valeur.isEmpty()) return null
        // Déjà une référence (@string/…, ?attr/…) : rien à extraire.
        if (valeur.startsWith("@") || valeur.startsWith("?")) return null
        if (!nomValide(nomRessource)) return null
        // La référence S'ÉCRIT sans l'échappement des littéraux (le `@`
        // initial reste une référence — [EditionAttributsXml.definirReference]).
        return EditionAttributsXml.definirReference(texte, span, nomAttribut, "@string/$nomRessource")
    }

    /**
     * L'édition du FICHIER DE VALEURS : l'entrée
     * `<string name="nom">valeur échappée</string>` s'insère avant la
     * fermeture `</resources>`, à l'INDENTATION détectée des entrées
     * existantes (repli 4 espaces). `null` = refus (cf. KDoc).
     */
    public fun entreeStrings(
        texteStrings: String,
        nomRessource: String,
        valeur: String,
    ): EditionAttributsXml.EditionAttributsXmlResultat? {
        if (!nomValide(nomRessource)) return null
        if (valeur.isEmpty()) return null
        // Pas un fichier de valeurs (ou tronqué) : on ne touche pas.
        if (!texteStrings.contains("</resources>")) return null
        // Le nom est DÉJÀ PRIS : l'utilisateur en choisit un autre.
        if (texteStrings.contains("name=\"$nomRessource\"")) return null
        val fermeture = texteStrings.indexOf("</resources>")
        val debutLigneFermeture = texteStrings.lastIndexOf('\n', (fermeture - 1).coerceAtLeast(0)) + 1
        val indentation =
            Regex("(?m)^([ \\t]*)<string")
                .find(texteStrings)
                ?.groupValues
                ?.get(1)
                .takeUnless { it.isNullOrEmpty() }
                ?: PAS_INDENTATION
        val injection =
            "$indentation<string name=\"$nomRessource\">${echapperValeur(valeur)}</string>\n"
        return EditionAttributsXml.EditionAttributsXmlResultat(
            debutLigneFermeture,
            debutLigneFermeture,
            injection,
        )
    }

    /**
     * L'échappement propre au TEXTE D'ÉLÉMENT des fichiers de valeurs :
     * entités XML, apostrophe/guillemet Android (`\'`, `\"`), retour
     * saisi → `\n` littéral.
     */
    private fun echapperValeur(valeur: String): String =
        valeur
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

    /** L'indentation de repli (aucune entrée existante à imiter). */
    private const val PAS_INDENTATION: String = "    "
}
