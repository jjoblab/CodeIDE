package jo.codeide.core.domain.templates

/*
 * Mini-langage d'expressions des templates (étape 8 — section 11).
 *
 * Grammaire (priorité croissante) :
 * ```
 * expression  := ou
 * ou           := et ( '||' et )*
 * et           := egalite ( '&&' egalite )*
 * egalite      := unaire ( ('==' | '!=') unaire )*
 * unaire       := '!' unaire | primaire
 * primaire     := identifiant | 'true' | 'false' | '"…"' | '(' expression ')'
 * ```
 *
 * Valeurs : chaînes et booléens uniquement. L'évaluation est **totale et
 * stricte** — identifiant inconnu, type inattendu ou expression invalide
 * échouent explicitement avec la position (jamais de `null` silencieux,
 * jamais d'évaluation de code arbitraire — parseur écrit à la main,
 * profondeur et taille bornées).
 *
 * Ce fichier définit l'AST, les valeurs et l'erreur ; l'analyse vit dans
 * [ExpressionParser], l'évaluation dans [ExpressionEvaluator].
 */

/** Erreur du mini-langage : message français + position (caractère). */
internal class ExpressionException(
    val position: Int,
    message: String,
) : Exception(message)

/** Nœud d'AST d'une expression analysée. */
internal sealed interface ExpressionNode {
    /** Référence à un paramètre ou une variable du contexte. */
    data class Identifiant(
        val nom: String,
        val position: Int,
    ) : ExpressionNode

    /** Littéral chaîne (guillemets doubles, échappements `\"` `\\` `\n` `\t`). */
    data class LitteralChaine(
        val valeur: String,
        val position: Int,
    ) : ExpressionNode

    /** Littéral booléen `true` / `false`. */
    data class LitteralBooleen(
        val valeur: Boolean,
        val position: Int,
    ) : ExpressionNode

    /** Négation logique `!`. */
    data class Non(
        val operande: ExpressionNode,
        val position: Int,
    ) : ExpressionNode

    /** Conjonction `&&` (évaluation totale : les deux opérandes sont vérifiés). */
    data class Et(
        val gauche: ExpressionNode,
        val droite: ExpressionNode,
        val position: Int,
    ) : ExpressionNode

    /** Disjonction `||`. */
    data class Ou(
        val gauche: ExpressionNode,
        val droite: ExpressionNode,
        val position: Int,
    ) : ExpressionNode

    /** Égalité `==` (types identiques uniquement). */
    data class Egal(
        val gauche: ExpressionNode,
        val droite: ExpressionNode,
        val position: Int,
    ) : ExpressionNode

    /** Différence `!=` (types identiques uniquement). */
    data class Different(
        val gauche: ExpressionNode,
        val droite: ExpressionNode,
        val position: Int,
    ) : ExpressionNode
}

/** Valeur d'une expression : chaîne ou booléen (jamais `null`). */
internal sealed interface ExpressionValue {
    /** Valeur chaîne. */
    data class Chaine(
        val valeur: String,
    ) : ExpressionValue {
        public override fun toString(): String = "chaîne « $valeur »"
    }

    /** Valeur booléenne. */
    data class Booleen(
        val valeur: Boolean,
    ) : ExpressionValue {
        public override fun toString(): String = "booléen $valeur"
    }
}

/** Contexte d'évaluation : identifiants connus (paramètres, variables). */
internal typealias ContexteExpressions = Map<String, ExpressionValue>

/**
 * Évaluateur de l'AST : strict en types (`&&` exige des booléens, `==` refuse
 * de comparer une chaîne et un booléen), explicite sur les identifiants
 * inconnus — le nom fautif et sa position accompagnent l'erreur.
 */
internal object ExpressionEvaluator {
    /** Évalue [noeud] dans [contexte]. */
    @Suppress("CyclomaticComplexMethod") // Six opérateurs strictement typés, un par branche.
    fun evaluer(
        noeud: ExpressionNode,
        contexte: ContexteExpressions,
    ): ExpressionValue =
        when (noeud) {
            is ExpressionNode.LitteralChaine -> {
                ExpressionValue.Chaine(noeud.valeur)
            }

            is ExpressionNode.LitteralBooleen -> {
                ExpressionValue.Booleen(noeud.valeur)
            }

            is ExpressionNode.Identifiant -> {
                contexte[noeud.nom] ?: throw ExpressionException(
                    noeud.position,
                    "identifiant inconnu « ${noeud.nom} » (paramètres et variables disponibles : " +
                        "${contexte.keys.sorted().joinToString(", ")})",
                )
            }

            is ExpressionNode.Non -> {
                val operande = evaluer(noeud.operande, contexte)
                if (operande !is ExpressionValue.Booleen) {
                    throw ExpressionException(
                        noeud.position,
                        "« ! » exige un booléen, reçu $operande",
                    )
                }
                ExpressionValue.Booleen(!operande.valeur)
            }

            is ExpressionNode.Et -> {
                val gauche = evaluer(noeud.gauche, contexte)
                val droite = evaluer(noeud.droite, contexte)
                if (gauche !is ExpressionValue.Booleen || droite !is ExpressionValue.Booleen) {
                    throw ExpressionException(
                        noeud.position,
                        "« && » exige deux booléens, reçu $gauche et $droite",
                    )
                }
                ExpressionValue.Booleen(gauche.valeur && droite.valeur)
            }

            is ExpressionNode.Ou -> {
                val gauche = evaluer(noeud.gauche, contexte)
                val droite = evaluer(noeud.droite, contexte)
                if (gauche !is ExpressionValue.Booleen || droite !is ExpressionValue.Booleen) {
                    throw ExpressionException(
                        noeud.position,
                        "« || » exige deux booléens, reçu $gauche et $droite",
                    )
                }
                ExpressionValue.Booleen(gauche.valeur || droite.valeur)
            }

            is ExpressionNode.Egal -> {
                comparer(noeud.position, noeud.gauche, noeud.droite, contexte, attendu = true)
            }

            is ExpressionNode.Different -> {
                comparer(noeud.position, noeud.gauche, noeud.droite, contexte, attendu = false)
            }
        }

    /** Comparaison `==`/`!=` : mêmes types uniquement, sinon échec explicite. */
    private fun comparer(
        position: Int,
        gauche: ExpressionNode,
        droite: ExpressionNode,
        contexte: ContexteExpressions,
        attendu: Boolean,
    ): ExpressionValue {
        val valeurGauche = evaluer(gauche, contexte)
        val valeurDroite = evaluer(droite, contexte)
        if (valeurGauche::class != valeurDroite::class) {
            throw ExpressionException(
                position,
                "impossible de comparer $valeurGauche et $valeurDroite (types différents)",
            )
        }
        val egaux = valeurGauche == valeurDroite
        return ExpressionValue.Booleen(if (attendu) egaux else !egaux)
    }
}
