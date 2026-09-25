package jo.codeide.feature.editor

/**
 * Scanner de symboles englobants pour le fil d'Ariane de l'éditeur
 * (v0.32.3, ADR 0054) — transposition de la `BreadcrumbBar` de la
 * bibliothèque code-editor (`view/chrome/BreadcrumbBar.java`), qui suit
 * la chaîne « fichier › classe › méthode » sous le caret.
 *
 * La bibliothèque cel-ui 3.37.0 expose bien le SPI
 * `jo.codeeditor.lang.SymbolProvider`, mais aucune langue embarquée ne
 * l'implémente (elles arrivent avec `cel-lsp`, hors périmètre) : le fil
 * d'Ariane de CodeIDE possède donc **son** scanner heuristique, en
 * lecteur seul sur le texte de la session.
 *
 * Principe : les déclarations Kotlin/Java (`class`, `object`,
 * `interface`, `enum class`, `record`, `fun`, méthodes Java) sont
 * repérées ligne à ligne ; leur **fin** suit la profondeur d'accolades
 * — une déclaration ouverte à la profondeur D se referme quand la
 * profondeur repasse sous D. Les constructions sans accolades (`fun x()
 * = …`) se referment sur la déclaration suivante de niveau égal ou plus
 * externe, ou à la fin du texte. Les lignes de commentaire sont
 * ignorées ; les accolades dans les chaînes restent une limite assumée
 * (affichage indicatif, jamais un pilote d'édition).
 */
internal object SymbolesEnglobants {
    /** Un symbole reconnu : nom affichable et bornes de portée. */
    internal data class Symbole(
        val nom: String,
        val debut: Int,
        val fin: Int,
    )

    /** Déclaration encore ouverte pendant le scan (profondeur d'ouverture). */
    private data class Ouverte(
        val nom: String,
        val debut: Int,
        val profondeur: Int,
    )

    /**
     * Extrait les symboles englobants à l'offset [caret] — le plus
     * externe en premier (fichier › classe › fonction), comme la
     * `BreadcrumbBar` de la bibliothèque.
     */
    fun englobants(
        texte: CharSequence,
        caret: Int,
    ): List<String> =
        if (texte.isEmpty() || caret < 0 || caret > texte.length) {
            emptyList()
        } else {
            symboles(texte)
                .filter { caret >= it.debut && caret <= it.fin }
                .sortedBy { it.debut }
                .map { it.nom }
        }

    /**
     * Scanne tout le texte : déclarations + bornes par profondeur.
     * Une déclaration est poussée avec la profondeur d'accolades qui
     * régnait au début de SA ligne ; elle est refermée quand la
     * profondeur repasse sous cette valeur (accolade fermante),
     * quand une déclaration sœur arrive, ou en fin de texte.
     */
    internal fun symboles(texte: CharSequence): List<Symbole> {
        val resultats = mutableListOf<Symbole>()
        val ouvertes = ArrayDeque<Ouverte>()
        var profondeur = 0
        var debutLigne = 0
        // Profondeur au DÉBUT de la ligne courante : les accolades de la
        // ligne sont déjà comptées quand son '\n' arrive — une
        // déclaration s'ouvre à la profondeur qui régnait quand la ligne
        // a commencé, pas à celle de sa fin.
        var profondeurDebutLigne = 0
        val n = texte.length

        var index = 0
        while (index < n) {
            when (texte[index]) {
                '\n' -> {
                    traiterFinDeLigne(
                        texte.subSequence(debutLigne, index).toString(),
                        debutLigne,
                        ouvertes,
                        resultats,
                        profondeurDebutLigne,
                    )
                    debutLigne = index + 1
                    profondeurDebutLigne = profondeur
                }

                '{' -> {
                    profondeur++
                }

                '}' -> {
                    profondeur--
                    refermerSousProfondeur(ouvertes, resultats, index, profondeur)
                }
            }
            index++
        }
        // Dernière ligne (sans '\n' final), puis tout ce qui reste
        // ouvert se referme à la fin du texte.
        traiterFinDeLigne(
            texte.subSequence(debutLigne, n).toString(),
            debutLigne,
            ouvertes,
            resultats,
            profondeurDebutLigne,
        )
        while (ouvertes.isNotEmpty()) {
            resultats += versSymbole(ouvertes.removeLast(), n)
        }
        return resultats
    }

    /** Fin de ligne atteinte : détecte une éventuelle déclaration et
     *  referme les sœurs sans accolades du même niveau. */
    private fun traiterFinDeLigne(
        ligne: String,
        debut: Int,
        ouvertes: ArrayDeque<Ouverte>,
        resultats: MutableList<Symbole>,
        profondeurDebutLigne: Int,
    ) {
        if (!estCommentaire(ligne) && AnalyseurLigne.evoqueDeclaration(ligne)) {
            val nom = AnalyseurLigne.nomDeDeclaration(ligne)
            if (nom != null) {
                while (ouvertes.isNotEmpty() && ouvertes.last().profondeur >= profondeurDebutLigne) {
                    resultats += versSymbole(ouvertes.removeLast(), debut)
                }
                ouvertes.addLast(Ouverte(nom, debut, profondeurDebutLigne))
            }
        }
    }

    /** Referme toute déclaration ouverte à [profondeur] ou au-delà. */
    private fun refermerSousProfondeur(
        ouvertes: ArrayDeque<Ouverte>,
        resultats: MutableList<Symbole>,
        fin: Int,
        profondeur: Int,
    ) {
        while (ouvertes.isNotEmpty() && ouvertes.last().profondeur >= profondeur) {
            resultats += versSymbole(ouvertes.removeLast(), fin)
        }
    }

    /** Une déclaration refermée devient symbole (bornes définitives). */
    private fun versSymbole(
        ouverte: Ouverte,
        fin: Int,
    ): Symbole = Symbole(ouverte.nom, ouverte.debut, fin)

    /** Ligne de commentaire (Kotlin/Java) : ignorée par le scanner. */
    private fun estCommentaire(ligne: String): Boolean {
        val contenu = ligne.trimStart()
        return contenu.startsWith("//") || contenu.startsWith("*") || contenu.startsWith("/*")
    }
}

/**
 * Reconnaissance d'une déclaration dans UNE ligne (Kotlin et Java) —
 * le collaborateur lexical de [SymbolesEnglobants].
 */
private object AnalyseurLigne {
    /** Modificateurs tolérés entre le début de ligne et le mot-clé de
     *  déclaration (Kotlin + Java, dans le désordre). */
    private val MODIFICATEURS =
        listOf(
            "public",
            "private",
            "protected",
            "internal",
            "open",
            "abstract",
            "final",
            "override",
            "sealed",
            "companion",
            "data",
            "enum",
            "inline",
            "suspend",
            "external",
            "actual",
            "expect",
            "const",
            "lateinit",
            "operator",
            "infix",
            "tailrec",
            "vararg",
            "noinline",
            "crossinline",
            "static",
            "native",
            "synchronized",
            "transient",
            "volatile",
            "default",
            "non-sealed",
        )

    /** Mots-clés qui ouvrent une déclaration nommée. */
    private val MOTS_CLES = listOf("class", "interface", "object", "fun", "record")

    /** Mots qui précèdent une expression, jamais une déclaration. */
    private val MOTS_NON_DECLARATIFS =
        listOf(
            "return",
            "if",
            "else",
            "for",
            "while",
            "when",
            "do",
            "try",
            "catch",
            "finally",
            "throw",
            "throws",
            "new",
            "val",
            "var",
            "package",
            "import",
            "this",
            "super",
            "constructor",
            "init",
        )

    /** Pré-filtre bon marché : la ligne évoque-t-elle une déclaration ?
     *  Mots-clés directs, `fun(` collé, ou candidate méthode Java
     *  (bloc ouvert + parenthèses — le vrai tri se fait plus loin). */
    fun evoqueDeclaration(ligne: String): Boolean =
        MOTS_CLES.any { ligne.contains("$it ") } ||
            ligne.trimStart().startsWith("fun(") ||
            (ligne.trimEnd().endsWith("{") && ligne.contains('('))

    /** Nom déclaré par la ligne, ou `null` — le parseur en trois temps :
     *  annotations élaguées, modificateurs consommés, puis mot-clé
     *  (Kotlin) ou motif `Type nom(…) {` (Java). */
    fun nomDeDeclaration(ligne: String): String? {
        val jetons = jetonsApresAnnotations(ligne) ?: return null
        val apresModificateurs = consommerModificateurs(jetons)
        val suite = motCleEtSuite(apresModificateurs)
        return if (suite != null) {
            val (motCle, apres) = suite
            if (motCle in MOTS_CLES) nomDepuisMotCle(apres) else nomMethodeJava(apresModificateurs, ligne)
        } else {
            null
        }
    }

    /** Élague les annotations de tête (`@Annot`, `@Annot(…)`), puis
     *  découpe en jetons — `null` si une annotation avale la ligne. */
    private fun jetonsApresAnnotations(ligne: String): List<String>? {
        var reste = ligne.trimStart()
        while (reste.startsWith("@")) {
            val fin = reste.indexOfFirst { it == ' ' || it == '(' || it == '\t' }
            if (fin == -1) return null
            reste = reste.substring(fin).trimStart()
        }
        return reste.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    /** Consomme les modificateurs de tête, renvoie le reste. */
    private fun consommerModificateurs(jetons: List<String>): List<String> {
        var restants = jetons
        while (restants.isNotEmpty() && restants.first() in MODIFICATEURS) {
            restants = restants.drop(1)
        }
        return restants
    }

    /** Mot-clé de déclaration et jetons qui le suivent ; « enum class »
     *  rend le second mot (le vrai mot-clé). */
    private fun motCleEtSuite(jetons: List<String>): Pair<String, List<String>>? {
        val motCle = jetons.firstOrNull() ?: return null
        return if (motCle == "enum" && jetons.getOrNull(1) == "class") {
            "class" to jetons.drop(2)
        } else {
            motCle to jetons.drop(1)
        }
    }

    /** Identifiant déclaré après le mot-clé Kotlin, généricité
     *  (`fun <T> …`) sautée — constructeurs et blocs rejetés. */
    private fun nomDepuisMotCle(apres: List<String>): String? {
        val brut = sauterGenerique(apres)?.firstOrNull()
        return if (brut != null && !brut.startsWith("(")) nomIdentifiant(brut) else null
    }

    /** Généricité d'un seul jeton (`<T, R>`) consommée ; `null` si
     *  inachevée (`<T` sans fermeture). */
    private fun sauterGenerique(apres: List<String>): List<String>? =
        when {
            apres.isEmpty() -> apres
            apres.first().startsWith("<") && apres.first().endsWith(">") -> apres.drop(1)
            apres.first().startsWith("<") -> null
            else -> apres
        }

    /** Méthode Java `public void main(String[] args) {` : le type de
     *  retour remplace le mot-clé. Reconnue seulement si la ligne ouvre
     *  un bloc (`{` finale) — une fin d'expression (`return foo()`)
     *  n'est jamais une déclaration. */
    private fun nomMethodeJava(
        jetons: List<String>,
        ligne: String,
    ): String? {
        if (jetons.size < 2 || !jetons[1].contains('(') || !ligne.trimEnd().endsWith("{")) {
            return null
        }
        return if (typeJavaValide(jetons[0])) nomIdentifiant(jetons[1].substringBefore('(')) else null
    }

    /** Type de retour plausible : un identifiant (génériques et
     *  tableaux tolérés), jamais un mot d'expression. */
    private fun typeJavaValide(type: String): Boolean =
        !type.contains('(') &&
            type !in MOTS_NON_DECLARATIFS &&
            type.matches(Regex("[A-Za-z_][A-Za-z0-9_.<>,\\[\\]]*"))

    /** Identifiant affichable : accents graves Kotlin et récepteur
     *  (`fun Foo.bar()`) élagués, suffixes d'héritage/générique coupés. */
    private fun nomIdentifiant(brut: String): String? {
        val nom =
            brut
                .trim('`', '(', '<')
                .substringBefore('(')
                .substringBefore(':')
                .substringAfterLast('.')
        return if (nom.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) nom else null
    }
}
