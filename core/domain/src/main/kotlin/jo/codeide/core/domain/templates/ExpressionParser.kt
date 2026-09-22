package jo.codeide.core.domain.templates

/**
 * Analyseur syntaxique du mini-langage d'expressions — **écrit à la main**
 * (étape 8 : aucune évaluation de code arbitraire, aucune dépendance de
 * parsing générique).
 *
 * Deux passes : lexique (jetons), puis descente récursive sur la grammaire
 * documentée dans [Expression.kt]. Bornes de sécurité vérifiées **avant** la
 * moindre récursion : longueur, nombre de jetons, profondeur — un manifeste
 * hostile ne peut ni saturer la pile ni faire diverger l'analyse.
 */
@Suppress("TooManyFunctions") // Lexique + descente récursive (ADR 0018).
internal object ExpressionParser {
    /** Longueur maximale d'une expression (manifestes contrôlés par nos soins). */
    internal const val LONGUEUR_MAX = 512

    /** Nombre maximal de jetons (les littéraux comptent pour un). */
    internal const val JETONS_MAX = 128

    /** Profondeur maximale d'imbrication (parenthèses et négations). */
    internal const val PROFONDEUR_MAX = 16

    /** Jeton lexical. */
    private sealed interface Jeton {
        val position: Int

        data class Identifiant(
            val nom: String,
            override val position: Int,
        ) : Jeton

        data class Chaine(
            val valeur: String,
            override val position: Int,
        ) : Jeton

        data class Booleen(
            val valeur: Boolean,
            override val position: Int,
        ) : Jeton

        data class Operateur(
            val symbole: String,
            override val position: Int,
        ) : Jeton

        data object Fin : Jeton {
            override val position: Int = -1
        }
    }

    /** Analyse [texte] en AST ; [ExpressionException] en cas d'erreur. */
    @Suppress("ThrowsCount") // Trois bornes de sécurité, un throw par refus explicite (ADR 0018).
    fun analyser(texte: String): ExpressionNode {
        if (texte.isEmpty()) {
            throw ExpressionException(0, "expression vide")
        }
        if (texte.length > LONGUEUR_MAX) {
            throw ExpressionException(0, "expression trop longue (${texte.length} > $LONGUEUR_MAX caractères)")
        }
        val jetons = lexique(texte)
        if (jetons.size > JETONS_MAX) {
            throw ExpressionException(0, "expression trop complexe (${jetons.size} > $JETONS_MAX jetons)")
        }
        val curseur = Curseur(jetons)
        val noeud = parseOu(curseur, profondeur = 0)
        curseur.attendreFin()
        return noeud
    }

    // ---------------------------------------------------------------- lexique

    /** Découpe [texte] en jetons ; bornes et caractères inconnus contrôlés. */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // Une branche par jeton du langage.
    private fun lexique(texte: String): List<Jeton> {
        val jetons = mutableListOf<Jeton>()
        var index = 0
        while (index < texte.length) {
            val caractere = texte[index]
            when {
                caractere.isWhitespace() -> {
                    index++
                }

                caractere == '"' -> {
                    val (litteral, suivant) = lireChaine(texte, index)
                    jetons += Jeton.Chaine(litteral, index)
                    index = suivant
                }

                caractere == '&' -> {
                    exigerDouble(texte, index, '&')
                    jetons += Jeton.Operateur("&&", index)
                    index += 2
                }

                caractere == '|' -> {
                    exigerDouble(texte, index, '|')
                    jetons += Jeton.Operateur("||", index)
                    index += 2
                }

                caractere == '=' -> {
                    exigerDouble(texte, index, '=')
                    jetons += Jeton.Operateur("==", index)
                    index += 2
                }

                caractere == '!' && index + 1 < texte.length && texte[index + 1] == '=' -> {
                    jetons += Jeton.Operateur("!=", index)
                    index += 2
                }

                caractere == '!' -> {
                    jetons += Jeton.Operateur("!", index)
                    index++
                }

                caractere == '(' -> {
                    jetons += Jeton.Operateur("(", index)
                    index++
                }

                caractere == ')' -> {
                    jetons += Jeton.Operateur(")", index)
                    index++
                }

                caractere.isLetterOrDigit() || caractere == '_' -> {
                    val debut = index
                    while (index < texte.length && (texte[index].isLetterOrDigit() || texte[index] == '_')) index++
                    val mot = texte.substring(debut, index)
                    jetons +=
                        when (mot) {
                            "true" -> Jeton.Booleen(true, debut)
                            "false" -> Jeton.Booleen(false, debut)
                            else -> Jeton.Identifiant(mot, debut)
                        }
                }

                else -> {
                    throw ExpressionException(index, "caractère inattendu « $caractere » à la position ${index + 1}")
                }
            }
        }
        return jetons + Jeton.Fin
    }

    /** Lit un littéral chaîne depuis un guillemet ouvrant ; échappements limités. */
    private fun lireChaine(
        texte: String,
        debut: Int,
    ): Pair<String, Int> {
        val contenu = StringBuilder()
        var index = debut + 1
        while (index < texte.length && texte[index] != '"') {
            val caractere = texte[index]
            if (caractere == '\\') {
                val suivant = texte.getOrNull(index + 1)
                when (suivant) {
                    '"', '\\' -> {
                        contenu.append(suivant)
                        index += 2
                    }

                    'n' -> {
                        contenu.append('\n')
                        index += 2
                    }

                    't' -> {
                        contenu.append('\t')
                        index += 2
                    }

                    else -> {
                        throw ExpressionException(index, "échappement inconnu « \\$suivant » dans un littéral chaîne")
                    }
                }
            } else {
                contenu.append(caractere)
                index++
            }
        }
        if (index >= texte.length) {
            throw ExpressionException(
                debut,
                "littéral chaîne non fermé (guillemet ouvrant à la position ${debut + 1})",
            )
        }
        return contenu.toString() to index + 1
    }

    /** Un opérateur binaire de caractères doit être doublé (`&&`, `||`, `==`). */
    private fun exigerDouble(
        texte: String,
        index: Int,
        attendu: Char,
    ) {
        if (texte.getOrNull(index + 1) != attendu) {
            throw ExpressionException(index, "opérateur incomplet « $attendu » à la position ${index + 1}")
        }
    }

    // -------------------------------------------------------------- syntaxe

    /** Curseur de jetons : consommation et jeton courant. */
    private class Curseur(
        private val jetons: List<Jeton>,
    ) {
        private var index = 0

        /** Jeton courant — la fin tenue pour acquise au-delà du dernier jeton. */
        val courant: Jeton get() = jetons.getOrElse(index) { Jeton.Fin }

        fun avancer(): Jeton = jetons.getOrElse(index) { Jeton.Fin }.also { index++ }

        /** Position lisible du jeton courant (fin ramenée au dernier jeton réel). */
        fun positionLisible(): Int =
            if (courant is Jeton.Fin) {
                maxOf(jetons.size - 2, 0)
            } else {
                courant.position
            }

        fun attendreFin() {
            if (courant != Jeton.Fin) {
                throw ExpressionException(
                    positionLisible(),
                    "jeton inattendu ${decrire(courant)} après la fin de l'expression",
                )
            }
        }
    }

    /** Symbole de l'opérateur courant, ou `null` si le jeton n'est pas un opérateur. */
    private fun symboleCourant(curseur: Curseur): String? = (curseur.courant as? Jeton.Operateur)?.symbole

    /** `expression := ou` — point d'entrée avec garde de profondeur. */
    private fun parseOu(
        curseur: Curseur,
        profondeur: Int,
    ): ExpressionNode {
        // Chaque parenthèse ou négation consomme un cran : la profondeur
        // compte les niveaux d'imbrication réels (15 parenthèses imbriquées
        // passent, la 16e échoue — la borne protège la pile d'appels).
        if (profondeur > PROFONDEUR_MAX) {
            throw ExpressionException(curseur.positionLisible(), "imbrication trop profonde (> $PROFONDEUR_MAX)")
        }
        var gauche = parseEt(curseur, profondeur)
        while (symboleCourant(curseur) == "||") {
            val position = curseur.avancer().position
            val droite = parseEt(curseur, profondeur)
            gauche = ExpressionNode.Ou(gauche, droite, position)
        }
        return gauche
    }

    /** `et := egalite ( '&&' egalite )*`. */
    private fun parseEt(
        curseur: Curseur,
        profondeur: Int,
    ): ExpressionNode {
        var gauche = parseEgalite(curseur, profondeur)
        while (symboleCourant(curseur) == "&&") {
            val position = curseur.avancer().position
            val droite = parseEgalite(curseur, profondeur)
            gauche = ExpressionNode.Et(gauche, droite, position)
        }
        return gauche
    }

    /** `egalite := unaire ( ('==' | '!=') unaire )*`. */
    private fun parseEgalite(
        curseur: Curseur,
        profondeur: Int,
    ): ExpressionNode {
        var gauche = parseUnaire(curseur, profondeur)
        while (true) {
            val symbole = symboleCourant(curseur)
            if (symbole != "==" && symbole != "!=") break
            val position = curseur.avancer().position
            val droite = parseUnaire(curseur, profondeur)
            gauche =
                if (symbole == "==") {
                    ExpressionNode.Egal(gauche, droite, position)
                } else {
                    ExpressionNode.Different(gauche, droite, position)
                }
        }
        return gauche
    }

    /** `unaire := '!' unaire | primaire`. */
    private fun parseUnaire(
        curseur: Curseur,
        profondeur: Int,
    ): ExpressionNode {
        if (symboleCourant(curseur) == "!") {
            val position = curseur.avancer().position
            return ExpressionNode.Non(parseUnaire(curseur, profondeur + 1), position)
        }
        return parsePrimaire(curseur, profondeur + 1)
    }

    /** `primaire := identifiant | booléen | chaîne | '(' expression ')'`. */
    @Suppress("ThrowsCount") // Un throw par jeton invalide — erreurs localisées (exigence étape 8).
    private fun parsePrimaire(
        curseur: Curseur,
        profondeur: Int,
    ): ExpressionNode {
        if (profondeur > PROFONDEUR_MAX) {
            throw ExpressionException(curseur.positionLisible(), "imbrication trop profonde (> $PROFONDEUR_MAX)")
        }
        return when (val jeton = curseur.avancer()) {
            is Jeton.Identifiant -> {
                ExpressionNode.Identifiant(jeton.nom, jeton.position)
            }

            is Jeton.Booleen -> {
                ExpressionNode.LitteralBooleen(jeton.valeur, jeton.position)
            }

            is Jeton.Chaine -> {
                ExpressionNode.LitteralChaine(jeton.valeur, jeton.position)
            }

            is Jeton.Operateur -> {
                if (jeton.symbole != "(") {
                    throw ExpressionException(
                        jeton.position,
                        "jeton inattendu « ${jeton.symbole} » où un terme est attendu",
                    )
                }
                val interne = parseOu(curseur, profondeur)
                val fermante = curseur.avancer()
                if (fermante !is Jeton.Operateur || fermante.symbole != ")") {
                    throw ExpressionException(
                        curseur.positionLisible(),
                        "parenthèse fermante attendue, reçu ${decrire(fermante)}",
                    )
                }
                interne
            }

            Jeton.Fin -> {
                throw ExpressionException(curseur.positionLisible(), "expression inattendument terminée")
            }
        }
    }

    /** Description d'un jeton pour les messages d'erreur. */
    private fun decrire(jeton: Jeton): String =
        when (jeton) {
            is Jeton.Identifiant -> "identifiant « ${jeton.nom} »"
            is Jeton.Chaine -> "littéral chaîne"
            is Jeton.Booleen -> "booléen ${jeton.valeur}"
            is Jeton.Operateur -> "« ${jeton.symbole} »"
            Jeton.Fin -> "fin d'expression"
        }
}
