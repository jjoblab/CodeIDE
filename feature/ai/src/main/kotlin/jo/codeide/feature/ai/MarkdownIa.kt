package jo.codeide.feature.ai

/**
 * Analyseur **Markdown minimal maison** (mission IA, lot IA-3, plan :
 * « titres, gras/italique, code en ligne, blocs de code avec langue,
 * listes, liens ») — aucune dépendance, rendu en vues (ADR 0002 : pas
 * de WebView), pensé pour le **flux incrémental** : un bloc de code non
 * fermé est un bloc provisoire qui s'allonge à chaque delta.
 *
 * Blocs reconnus :
 * - titres `#`, `##`, `###` (niveaux > 3 traités comme `###`) ;
 * - blocs de code ```` ```langue ```` … ```` ``` ```` (ouvrant non fermé
 *   = bloc **provisoire**, jamais perdu en flux) ;
 * - listes `- ` / `* ` (un élément par ligne, consécutives) ;
 * - tout le reste = paragraphe (lignes consécutives jointes par un
 *   saut — le rendu des spans gras/italique/code/lien vit dans
 *   [SpansMarkdown]).
 */
public sealed interface BlocMarkdown {
    /** Titre de niveau 1 à 3. */
    public data class Titre(
        public val niveau: Int,
        public val texte: String,
    ) : BlocMarkdown

    /** Paragraphe (texte brut — les spans se posent au rendu). */
    public data class Paragraphe(
        public val texte: String,
    ) : BlocMarkdown

    /** Bloc de code (langue optionnelle) — [provisoire] = ouvrant non fermé. */
    public data class BlocCode(
        public val langue: String?,
        public val code: String,
        public val provisoire: Boolean = false,
    ) : BlocMarkdown

    /** Liste à puces (éléments sans le marqueur). */
    public data class Liste(
        public val elements: List<String>,
    ) : BlocMarkdown
}

/** Analyseur pur (testable en JVM). */
public object MarkdownIa {
    private const val MARQUEUR_CODE = "```"

    /**
     * Analyse le texte complet en blocs.
     *
     * `@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")`
     * justifiées : la fonction EST l'automate d'analyse — une branche
     * (et un `continue`) par constructe Markdown fermé (code, titres,
     * listes, paragraphes) ; la complexité vient du nombre de
     * constructes, pas d'imbrication (règle 8).
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    public fun parser(texte: String): List<BlocMarkdown> {
        val blocs = mutableListOf<BlocMarkdown>()
        var paragraphe = mutableListOf<String>()
        // L'état « en code » est SÉPARÉ de la langue : un bloc ouvert
        // SANS langue (``` nu — Markdown valide) doit aussi consommer
        // ses lignes et être restitué (fermé ou provisoire).
        var enCode = false
        var codeLangue: String? = null
        var codeContenu = mutableListOf<String>()

        fun cloreParagraphe() {
            if (paragraphe.isNotEmpty()) {
                blocs.add(BlocMarkdown.Paragraphe(paragraphe.joinToString("\n")))
                paragraphe = mutableListOf()
            }
        }

        for (ligneBrute in texte.split("\n")) {
            val ligne = ligneBrute.trimEnd()
            if (ligne.startsWith(MARQUEUR_CODE)) {
                if (enCode) {
                    // Fermant : le bloc est complet.
                    blocs.add(BlocMarkdown.BlocCode(codeLangue, codeContenu.joinToString("\n")))
                    enCode = false
                    codeLangue = null
                    codeContenu = mutableListOf()
                } else {
                    cloreParagraphe()
                    enCode = true
                    codeLangue = ligne.removePrefix(MARQUEUR_CODE).trim().ifBlank { null }
                }
                continue
            }
            if (enCode) {
                codeContenu.add(ligneBrute)
                continue
            }
            val titre = regexTitre.matchEntire(ligne)
            if (titre != null) {
                cloreParagraphe()
                blocs.add(BlocMarkdown.Titre(titre.groupValues[1].length, titre.groupValues[2].trim()))
                continue
            }
            if (regexPuce.containsMatchIn(ligne)) {
                cloreParagraphe()
                val derniere = blocs.lastOrNull()
                if (derniere is BlocMarkdown.Liste) {
                    blocs[blocs.lastIndex] = derniere.copy(elements = derniere.elements + puceDe(ligne))
                } else {
                    blocs.add(BlocMarkdown.Liste(listOf(puceDe(ligne))))
                }
                continue
            }
            if (ligne.isBlank()) {
                cloreParagraphe()
            } else {
                paragraphe.add(ligne.trim())
            }
        }
        if (enCode) {
            // Ouvrant non fermé (flux en cours) : bloc provisoire.
            blocs.add(BlocMarkdown.BlocCode(codeLangue, codeContenu.joinToString("\n"), provisoire = true))
        }
        cloreParagraphe()
        return blocs
    }

    private fun puceDe(ligne: String): String = ligne.replaceFirst(regexPuce, "").trim()

    private val regexTitre = Regex("""^(#{1,3})\s+(.*)$""")
    private val regexPuce = Regex("""^[-*]\s+""")
}
