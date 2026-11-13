package jo.codeide.feature.ai

/**
 * Analyseur **Markdown minimal maison** (mission IA, lot IA-3, plan :
 * « titres, gras/italique, code en ligne, blocs de code avec langue,
 * listes, liens » ; C3, retour propriétaire : listes ordonnées,
 * citations, séparateurs, tableaux, imbrication, cases à cocher) —
 * aucune dépendance, rendu en vues (ADR 0002 : pas de WebView), pensé
 * pour le **flux incrémental** : un bloc de code non fermé est un bloc
 * provisoire qui s'allonge à chaque delta.
 *
 * Blocs reconnus :
 * - titres `#`, `##`, `###` (niveaux > 3 traités comme `###`) ;
 * - blocs de code ```` ```langue ```` … ```` ``` ```` (ouvrant non fermé
 *   = bloc **provisoire**, jamais perdu en flux) ;
 * - listes `- ` / `* ` (imbrication par indentation, 2 espaces par
 *   niveau, cases à cocher `[ ]` / `[x]`) ;
 * - listes ordonnées `1. ` (le marqueur repart à 1 à chaque bloc,
 *   la numérotation est recalcée au rendu) ;
 * - citations `> ` (lignes consécutives jointes) ;
 * - séparateurs `---`, `***`, `___` (au moins trois, seuls sur la
 *   ligne — un `---` sous un paragraphe n'est PAS un titre setext) ;
 * - tableaux `| a | b |` (ligne séparatrice `---` exigée sous
 *   l'en-tête, alignements `:--` `:-:` `--:` lus mais rendus au
 *   monospace) ;
 * - tout le reste = paragraphe (lignes consécutives jointes par un
 *   saut — le rendu des spans gras/italique/code/lien vit dans
 *   [SpansMarkdown]).
 */
public sealed interface BlocMarkdown {
    /** Titre de niveau 1 à 3. */
    public data class Titre(public val niveau: Int, public val texte: String) : BlocMarkdown

    /** Paragraphe (texte brut — les spans se posent au rendu). */
    public data class Paragraphe(public val texte: String) : BlocMarkdown

    /** Bloc de code (langue optionnelle) — [provisoire] = ouvrant non fermé. */
    public data class BlocCode(
        public val langue: String?,
        public val code: String,
        public val provisoire: Boolean = false,
    ) : BlocMarkdown

    /** Élément de liste à puces (C3, retour propriétaire). */
    public data class ElementListe(
        public val texte: String,

        /** Niveau d'imbrication (0 = racine, +1 par indentation de 2). */
        public val niveau: Int = 0,

        /** Case cochée — `null` = pas une case (rendering ☐/☑ sinon). */
        public val coche: Boolean? = null,
    )

    /**
     * Liste (puces par défaut, ordonnée si [ordonee] — C3, retour
     * propriétaire) : les éléments portent leur niveau d'imbrication.
     */
    public data class Liste(public val elements: List<ElementListe>, public val ordonee: Boolean = false) :
        BlocMarkdown

    /** Citation `> ` (lignes consécutives jointes — C3, retour propriétaire). */
    public data class Citation(public val texte: String) : BlocMarkdown

    /** Séparateur horizontal `---` / `***` / `___` (C3, retour propriétaire). */
    public data object Separateur : BlocMarkdown

    /**
     * Tableau Markdown (C3, retour propriétaire) : en-têtes, lignes
     * et alignements par colonne (`null` = gauche par défaut).
     */
    public data class Tableau(
        public val entetes: List<String>,
        public val lignes: List<List<String>>,
        public val alignements: List<AlignementColonne?> = emptyList(),
    ) : BlocMarkdown
}

/** Alignement déclaré d'une colonne de tableau (`:--` `:-:` `--:`). */
public enum class AlignementColonne { GAUCHE, CENTRE, DROITE }

/** Analyseur pur (testable en JVM). */
public object MarkdownIa {
    private const val MARQUEUR_CODE = "```"

    /**
     * Analyse le texte complet en blocs.
     *
     * `@Suppress("LongMethod", "CyclomaticComplexMethod",
     * "LoopWithTooManyJumpStatements")` justifiées : la fonction EST
     * l'automate d'analyse en UNE PASSE — une branche (et un `continue`)
     * par constructe Markdown fermé (code, titres, listes, citations,
     * séparateurs, tableaux, paragraphes) ; la longueur et la
     * complexité viennent du NOMBRE de constructes, pas d'imbrication
     * (règle 8). La découper fragmenterait l'état du flux incrémental
     * (bloc de code provisoire, paragraphe ouvert) entre des
     * sous-fonctions qui se le repasseraient — la boucle unique garde
     * les invariants VISIBLES et testés.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
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

        val lignes = texte.split("\n")
        var index = 0
        while (index < lignes.size) {
            val ligneBrute = lignes[index]
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
                index++
                continue
            }
            if (enCode) {
                codeContenu.add(ligneBrute)
                index++
                continue
            }
            val titre = regexTitre.matchEntire(ligne.trim())
            if (titre != null) {
                cloreParagraphe()
                blocs.add(BlocMarkdown.Titre(titre.groupValues[1].length, titre.groupValues[2].trim()))
                index++
                continue
            }
            // Séparateur horizontal : au moins trois marqueurs, seuls
            // sur la ligne (C3) — AVANT la puce (un `* * *` n'est pas
            // une liste) et avant la liste ordonnée.
            if (regexSeparateur.matches(ligne.trim())) {
                cloreParagraphe()
                blocs.add(BlocMarkdown.Separateur)
                index++
                continue
            }
            // Liste (à puces ou ordonnée — C3 : imbrication + cases).
            val element = elementListeDe(ligneBrute)
            if (element != null) {
                cloreParagraphe()
                val ordonnee = regexListeOrdonnee.containsMatchIn(ligneBrute.trim())
                val derniere = blocs.lastOrNull()
                if (derniere is BlocMarkdown.Liste && derniere.ordonee == ordonnee) {
                    blocs[blocs.lastIndex] = derniere.copy(elements = derniere.elements + element)
                } else {
                    blocs.add(BlocMarkdown.Liste(listOf(element), ordonnee))
                }
                index++
                continue
            }
            // Citation : lignes `> ` consécutives jointes (C3).
            if (ligne.trimStart().startsWith(">")) {
                cloreParagraphe()
                index = consommerCitation(lignes, index, blocs)
                continue
            }
            // Tableau : ligne ouvrante `| … |` SUIVIE d'un séparateur
            // de colonnes (C3) — sinon c'est une ligne de paragraphe.
            if (estLigneTableau(ligne.trim()) && index + 1 < lignes.size &&
                estSeparateurTableau(lignes[index + 1].trim())
            ) {
                cloreParagraphe()
                index = consommerTableau(lignes, index, blocs)
                continue
            }
            if (ligne.isBlank()) {
                cloreParagraphe()
            } else {
                paragraphe.add(ligne.trim())
            }
            index++
        }
        if (enCode) {
            // Ouvrant non fermé (flux en cours) : bloc provisoire.
            blocs.add(BlocMarkdown.BlocCode(codeLangue, codeContenu.joinToString("\n"), provisoire = true))
        }
        cloreParagraphe()
        return blocs
    }

    /**
     * Consomme un bloc de CITATION à partir de [index] (première ligne
     * `> `) : joint les lignes `> ` consécutives et rend l'index de
     * reprise (C3, retour propriétaire — extrait du parseur pour la
     * règle LongMethod).
     */
    private fun consommerCitation(lignes: List<String>, index: Int, blocs: MutableList<BlocMarkdown>): Int {
        val lignesCitation = mutableListOf(lignes[index].trimStart().removePrefix(">").trim())
        var suite = index + 1
        while (suite < lignes.size && lignes[suite].trimEnd().trimStart().startsWith(">")) {
            lignesCitation.add(lignes[suite].trimEnd().trimStart().removePrefix(">").trim())
            suite++
        }
        blocs.add(BlocMarkdown.Citation(lignesCitation.joinToString("\n")))
        return suite
    }

    /**
     * Consomme un TABLEAU à partir de [index] (en-têtes + ligne
     * séparatrice déjà vérifiées) : lit les rangées `| … |`
     * consécutives et rend l'index de reprise (C3, retour
     * propriétaire — extrait du parseur pour la règle LongMethod).
     */
    private fun consommerTableau(lignes: List<String>, index: Int, blocs: MutableList<BlocMarkdown>): Int {
        val entetes = cellulesDe(lignes[index].trim())
        val alignements = alignementsDe(lignes[index + 1].trim())
        var suite = index + 2
        val rangees = mutableListOf<List<String>>()
        while (suite < lignes.size && estLigneTableau(lignes[suite].trim())) {
            rangees.add(cellulesDe(lignes[suite].trim()))
            suite++
        }
        blocs.add(BlocMarkdown.Tableau(entetes, rangees, alignements))
        return suite
    }

    /**
     * Élément de liste d'une ligne (C3, retour propriétaire) : puce
     * `- `/`* ` ou ordinal `N. `, niveau d'imbrication par indentation
     * (2 espaces par niveau, plafonné à 2), case à cocher `[ ]`/`[x]`.
     * `null` si la ligne n'ouvre pas d'élément.
     */
    private fun elementListeDe(ligneBrute: String): BlocMarkdown.ElementListe? {
        val indentation = ligneBrute.length - ligneBrute.trimStart().length
        val ligne = ligneBrute.trim()
        val ordre = regexListeOrdonnee.find(ligne)
        val puce = regexPuce.find(ligne)
        val marqueurFin = when {
            ordre != null -> ordre.range.last + 1
            puce != null -> puce.range.last + 1
            else -> return null
        }
        var texte = ligne.substring(marqueurFin).trim()
        var coche: Boolean? = null
        val caseCochee = regexCase.find(texte)
        if (caseCochee != null) {
            coche = caseCochee.groupValues[1].equals("x", ignoreCase = true)
            texte = texte.substring(caseCochee.range.last + 1).trim()
        }
        val niveau = (indentation / ESPACES_PAR_NIVEAU).coerceAtMost(NIVEAU_MAX)
        return BlocMarkdown.ElementListe(texte = texte, niveau = niveau, coche = coche)
    }

    /** Ligne de tableau : commence ET finit par `|` avec au moins une cellule. */
    private fun estLigneTableau(ligne: String): Boolean = ligne.length > 1 &&
        ligne.startsWith("|") &&
        ligne.lastIndexOf("|") > 0 &&
        ligne.drop(1).contains("|")

    /** Séparateur de tableau : `| --- | :--: |` (au moins un tiret par colonne). */
    private fun estSeparateurTableau(ligne: String): Boolean =
        estLigneTableau(ligne) && cellulesDe(ligne).all { it.matches(regexColonneTableau) } &&
            cellulesDe(ligne).any { it.isNotBlank() }

    /** Cellules d'une ligne de tableau (sans les pipes de bord). */
    private fun cellulesDe(ligne: String): List<String> = ligne
        .removePrefix("|")
        .removeSuffix("|")
        .split("|")
        .map { it.trim() }

    /** Alignements déclarés d'une ligne séparatrice (`:--`, `:-:`, `--:`). */
    private fun alignementsDe(ligne: String): List<AlignementColonne?> = cellulesDe(ligne).map { colonne ->
        when {
            colonne.startsWith(":") && colonne.endsWith(":") -> AlignementColonne.CENTRE
            colonne.endsWith(":") -> AlignementColonne.DROITE
            else -> null
        }
    }

    private val regexTitre = Regex("""^(#{1,3})\s+(.*)$""")
    private val regexPuce = Regex("""^[-*]\s+""")
    private val regexListeOrdonnee = Regex("""^\d+[.)]\s+""")
    private val regexCase = Regex("""^\[([ xX])]\s*""")
    private val regexSeparateur = Regex("""^([-*_])(\s*\1){2,}$""")
    private val regexColonneTableau = Regex("""^:?-{1,}:?$""")

    private const val ESPACES_PAR_NIVEAU = 2
    private const val NIVEAU_MAX = 2
}
