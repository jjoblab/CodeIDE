package jo.codeide.preview.xml

/**
 * RENOMMAGE D'IDENTIFIANT (PE8a — le « renommage : à prévoir » laissé par
 * le prompt PE7e § 3) — FONCTIONS PURES sur le TEXTE du document, sans
 * Android : la DÉCLARATION `android:id="@+id/x"` de la vue au span ET
 * TOUTES les RÉFÉRENCES `@id/x` / `@+id/x` du MÊME fichier (contraintes
 * `app:layout_constraint*_to*_Of`, `tools:` quelconques) deviennent
 * `nouveau`, en UNE édition [EditionAttributsXml.EditionAttributsXmlResultat]
 * couvrant de la première à la dernière occurrence — un seul geste
 * d'annulation dans l'éditeur (contrat PE7d).
 *
 * Hors périmètre DOCUMENTÉ (prompt PE7e § 3, signalé dans l'interface) :
 * les références du CODE (`R.id.x` — elles suivent la ressource compilée,
 * pas ce texte), les AUTRES fichiers de layout et les fichiers de
 * ressources. Le renommage reste donc un geste de layout : l'IDE ne
 * prétend pas faire un refactor multi-fichiers.
 *
 * Refus (philosophie X4 — `null`, jamais d'écriture hasardeuse) :
 * nom nouveau INVALIDE (aapt2 : lettre d'abord, puis lettres/chiffres/« _ »),
 * identique à l'actuel, DÉJÀ PRIS dans le fichier, span périmé, vue sans
 * `android:id`, identifiant de PLATEFORME (`@android:id/…`) ou nom actuel
 * hors aapt2 (le renommer casserait la correspondance).
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) : ReturnCount —
 * chaque garde est une issue DISTINCTE du refus (nom invalide ≠ collision
 * ≠ sans id ≠ plateforme) ; les noyer dans une structure unique
 * brouillerait la raison du refus.
 */
@Suppress("ReturnCount")
public object RenommageIdentifiant {
    /** L'attribut porteur de l'identifiant. */
    private const val ATTR_ID: String = "android:id"

    /** Le nom de ressource Android VALIDE (aapt2 : lettre d'abord). */
    private val REGEX_NOM: Regex = Regex("[A-Za-z][A-Za-z0-9_]*")

    /**
     * Le nom NOUVEAU est-il acceptable pour aapt2 (lettre d'abord, puis
     * lettres/chiffres/« _ ») ? Exposé au chrome : il valide AVANT d'appeler
     * le renommage (retour immédiat à la frappe validée, miroir PE7e).
     */
    public fun nomValide(nom: String): Boolean = REGEX_NOM.matches(nom)

    /**
     * Les OCCURRENCES d'un identifiant dans le fichier : `@+id/x`
     * (déclaration) comme `@id/x` (référence). La lettre qui suit doit
     * CLORE le nom (jamais `@id/x2` attrapé pour `x`, jamais un sous-nom à
     * point) ; les identifiants de PLATEFORME (`@android:id/x`) ne
     * matchent pas — leur « android: » se dresse entre « @ » et « id/ ».
     */
    private fun occurrences(
        texte: String,
        nom: String,
    ): List<MatchResult> = Regex("""@\+?id/${Regex.escape(nom)}(?![A-Za-z0-9_.])""").findAll(texte).toList()

    /**
     * L'identifiant ACTUEL de la vue au span (le nom SANS préfixe) :
     * `null` si le span est périmé, la vue n'a pas d'`android:id`, ou
     * l'identifiant n'est pas renommable (plateforme `@android:id/…`, nom
     * hors aapt2 — cf. KDoc de l'objet).
     */
    public fun identifiantActuel(
        texte: String,
        span: InsertionXml.SpanParent,
    ): String? {
        val valeur = EditionAttributsXml.valeurAttribut(texte, span, ATTR_ID) ?: return null
        val nom =
            when {
                valeur.startsWith("@+id/") -> valeur.removePrefix("@+id/")
                valeur.startsWith("@id/") -> valeur.removePrefix("@id/")
                else -> return null
            }
        return if (REGEX_NOM.matches(nom)) nom else null
    }

    /**
     * RENOMME l'identifiant de la vue au span : déclaration + références
     * du fichier en UNE édition. `null` = refus (cf. KDoc de l'objet) —
     * l'appelant recharge le volet et ne touche jamais au texte.
     */
    public fun renommer(
        texte: String,
        span: InsertionXml.SpanParent,
        nouveau: String,
    ): EditionAttributsXml.EditionAttributsXmlResultat? {
        if (!REGEX_NOM.matches(nouveau)) return null
        val ancien = identifiantActuel(texte, span) ?: return null
        if (ancien == nouveau) return null
        // Déjà pris : une déclaration OU une référence porterait déjà ce
        // nom — renommer dessus créerait deux vues au même identifiant.
        if (occurrences(texte, nouveau).isNotEmpty()) return null
        val trouvees = occurrences(texte, ancien)
        // Incohérent : l'id lu sur la balise ne se retrouve nulle part
        // (span périmé, document réécrit) — refus défensif.
        if (trouvees.isEmpty()) return null
        val debut = trouvees.first().range.first
        val fin = trouvees.last().range.last + 1
        val reconstruit = StringBuilder(fin - debut)
        var curseur = debut
        for (trouvee in trouvees) {
            reconstruit.append(texte, curseur, trouvee.range.first)
            // Le préfixe porté par l'occurrence (@+id/ ou @id/) RESTE : la
            // déclaration garde son « + » (création), les références le leur.
            reconstruit.append(trouvee.value.substring(0, trouvee.value.lastIndexOf('/') + 1))
            reconstruit.append(nouveau)
            curseur = trouvee.range.last + 1
        }
        reconstruit.append(texte, curseur, fin)
        return EditionAttributsXml.EditionAttributsXmlResultat(debut, fin, reconstruit.toString())
    }
}
