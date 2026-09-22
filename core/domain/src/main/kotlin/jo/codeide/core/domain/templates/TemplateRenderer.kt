package jo.codeide.core.domain.templates

/**
 * Erreur de rendu d'un template : fichier et ligne toujours présents
 * (exigence de l'étape 8 : échec explicite, jamais de `{{…}}` résiduel).
 */
internal class TemplateRenderException(
    val fichier: String,
    val ligne: Int,
    message: String,
) : Exception("[$fichier:$ligne] $message") {
    /**
     * Variante sans fichier (filtres, validateurs appelés hors rendu de
     * fichier) : la ligne seule qualifie l'erreur.
     */
    internal constructor(
        ligne: Int,
        message: String,
    ) : this("<moteur>", ligne, message)
}

/** Nœud d'un fichier de template analysé. */
internal sealed interface TemplateNode {
    /** Texte brut (échappements `\{{` déjà résolus). */
    data class Texte(
        val texte: String,
    ) : TemplateNode

    /** `{{nom}}` ou `{{nom|filtre}}`. */
    data class Variable(
        val nom: String,
        val filtre: String?,
        val ligne: Int,
    ) : TemplateNode

    /** `{{#if expr}}…{{#else}}…{{/if}}` (imbrication bornée). */
    data class Conditionnel(
        val expression: ExpressionNode,
        val alors: List<TemplateNode>,
        val sinon: List<TemplateNode>,
        val ligne: Int,
    ) : TemplateNode

    /** `{{t:clé}}` — dictionnaire i18n du modèle. */
    data class Traduction(
        val cle: String,
        val ligne: Int,
    ) : TemplateNode
}

/**
 * Moteur de substitution (étape 8 — section 11).
 *
 * Analyse puis rend un contenu `.tpl` :
 * - `{{variable}}`, `{{variable|filtre}}` ;
 * - `{{#if expression}}…{{#else}}…{{/if}}` (expression du mini-langage) ;
 * - `{{t:clé}}` (i18n du modèle, repli sur l'anglais géré en amont) ;
 * - `\{{` produit un `{{` littéral.
 *
 * Tout `{{` qui n'est pas une balise **valide** est une erreur explicite
 * (fichier + ligne) : il ne reste jamais de marqueur non traité dans la
 * sortie. Les identifiants inconnus, filtres inconnus et clés i18n
 * manquantes échouent de la même façon.
 */
internal object TemplateRenderer {
    /** Profondeur maximale d'imbrication des blocs `{{#if}}`. */
    private const val PROFONDEUR_MAX = 16

    /** Marqueur d'ouverture d'une balise. */
    private const val OUVERTURE = "{{"

    /** Motif d'un identifiant (variable ou filtre). */
    private val MOTIF_IDENTIFIANT = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

    /** Motif d'une clé i18n (points, tirets et alphanumériques). */
    private val MOTIF_CLE_I18N = Regex("[a-zA-Z0-9_.-]+")

    /** Analyse [source] ; [fichier] sert aux messages d'erreur. */
    fun analyser(
        source: String,
        fichier: String,
    ): List<TemplateNode> {
        val analyseur = Analyseur(source, fichier)
        return analyseur.analyserTout()
    }

    /** Rend des nœuds analysés avec le contexte et le dictionnaire i18n. */
    fun rendre(
        noeuds: List<TemplateNode>,
        contexte: ContexteExpressions,
        dictionnaire: Map<String, String>,
        fichier: String,
    ): String {
        val sortie = StringBuilder()
        noeuds.forEach { sortie.append(rendreNoeud(it, contexte, dictionnaire, fichier)) }
        return sortie.toString()
    }

    /** Rend un fichier complet : analyse puis substitution. */
    fun rendreFichier(
        source: String,
        contexte: ContexteExpressions,
        dictionnaire: Map<String, String>,
        fichier: String,
    ): String = rendre(analyser(source, fichier), contexte, dictionnaire, fichier)

    @Suppress("SwallowedException") // L'exception est transformée en erreur typée fichier + ligne.
    private fun rendreNoeud(
        noeud: TemplateNode,
        contexte: ContexteExpressions,
        dictionnaire: Map<String, String>,
        fichier: String,
    ): String =
        when (noeud) {
            is TemplateNode.Texte -> {
                noeud.texte
            }

            is TemplateNode.Variable -> {
                val valeur =
                    contexte[noeud.nom]
                        ?: throw TemplateRenderException(
                            fichier,
                            noeud.ligne,
                            "variable inconnue « ${noeud.nom} » (disponibles : " +
                                "${contexte.keys.sorted().joinToString(", ")})",
                        )
                val brut =
                    when (valeur) {
                        is ExpressionValue.Chaine -> valeur.valeur
                        is ExpressionValue.Booleen -> valeur.valeur.toString()
                    }
                if (noeud.filtre == null) {
                    brut
                } else {
                    TemplateFilters.appliquer(noeud.filtre, brut, noeud.ligne)
                }
            }

            is TemplateNode.Conditionnel -> {
                val condition =
                    try {
                        ExpressionEvaluator.evaluer(noeud.expression, contexte)
                    } catch (erreur: ExpressionException) {
                        throw TemplateRenderException(
                            fichier,
                            noeud.ligne,
                            "expression invalide : ${erreur.message}",
                        )
                    }
                if (condition !is ExpressionValue.Booleen) {
                    throw TemplateRenderException(
                        fichier,
                        noeud.ligne,
                        "la condition d'un bloc {{#if}} doit être booléenne",
                    )
                }
                val branche = if (condition.valeur) noeud.alors else noeud.sinon
                val sortie = StringBuilder()
                branche.forEach { sortie.append(rendreNoeud(it, contexte, dictionnaire, fichier)) }
                sortie.toString()
            }

            is TemplateNode.Traduction -> {
                dictionnaire[noeud.cle]
                    ?: throw TemplateRenderException(fichier, noeud.ligne, "clé i18n manquante « ${noeud.cle} »")
            }
        }

    /** Analyse d'un contenu : curseur, suivi de ligne, imbrication bornée. */
    private class Analyseur(
        private val source: String,
        private val fichier: String,
    ) {
        private var index = 0
        private var ligne = 1

        fun analyserTout(): List<TemplateNode> {
            val (noeuds, arret) = analyserBranche(stopSurSinon = false, stopSurFin = false, profondeur = 0)
            if (arret != null) {
                throw TemplateRenderException(fichier, ligne, "« $arret » sans bloc {{#if}} ouvert")
            }
            return noeuds
        }

        /**
         * Analyse jusqu'au marqueur d'arrêt de cette imbrication.
         *
         * @return les nœuds et le marqueur d'arrêt rencontré (`null` = fin
         * du fichier).
         */
        @Suppress("NestedBlockDepth", "ThrowsCount") // Flux texte : boucles imbriquées, un throw par erreur localisée.
        private fun analyserBranche(
            stopSurSinon: Boolean,
            stopSurFin: Boolean,
            profondeur: Int,
        ): Pair<List<TemplateNode>, String?> {
            if (profondeur > PROFONDEUR_MAX) {
                throw TemplateRenderException(
                    fichier,
                    ligne,
                    "imbrication de blocs {{#if}} trop profonde (> $PROFONDEUR_MAX)",
                )
            }
            val noeuds = mutableListOf<TemplateNode>()
            val texte = StringBuilder()
            while (index < source.length) {
                when {
                    // Échappement \{{ : un accolade littéral.
                    source[index] == '\\' && source.startsWith("$OUVERTURE", index + 1) -> {
                        texte.append(OUVERTURE)
                        index += 1 + OUVERTURE.length
                    }

                    source.startsWith(OUVERTURE, index) -> {
                        viderTexte(noeuds, texte)
                        val arret = traiterBalise(noeuds, profondeur)
                        if (arret != null) {
                            if (arret == "{{#else}}" && !stopSurSinon) {
                                throw TemplateRenderException(fichier, ligne, "{{#else}} sans bloc {{#if}} ouvert")
                            }
                            if (arret == "{{/if}}" && !stopSurFin) {
                                throw TemplateRenderException(fichier, ligne, "{{/if}} sans bloc {{#if}} ouvert")
                            }
                            return Pair(noeuds, arret)
                        }
                    }

                    else -> {
                        if (source[index] == '\n') ligne++
                        texte.append(source[index])
                        index++
                    }
                }
            }
            viderTexte(noeuds, texte)
            return Pair(noeuds, null)
        }

        /** Pousse le texte accumulé (s'il est non vide) dans les nœuds. */
        private fun viderTexte(
            noeuds: MutableList<TemplateNode>,
            texte: StringBuilder,
        ) {
            if (texte.isNotEmpty()) {
                noeuds += TemplateNode.Texte(texte.toString())
                texte.setLength(0)
            }
        }

        /**
         * Traite une balise ouvrante `{{…}}` ; retourne le marqueur d'arrêt
         * consommé (`{{#else}}`/`{{/if}}`) ou `null`.
         */
        private fun traiterBalise(
            noeuds: MutableList<TemplateNode>,
            profondeur: Int,
        ): String? =
            when {
                source.startsWith("{{#if", index) -> {
                    noeuds += analyserConditionnel(profondeur)
                    null
                }

                source.startsWith("{{#else}}", index) -> {
                    index += "{{#else}}".length
                    "{{#else}}"
                }

                source.startsWith("{{/if}}", index) -> {
                    index += "{{/if}}".length
                    "{{/if}}"
                }

                source.startsWith("{{t:", index) -> {
                    noeuds += analyserTraduction()
                    null
                }

                else -> {
                    noeuds += analyserVariable()
                    null
                }
            }

        /** `{{#if expression}}branche{{#else}}branche{{/if}}`. */
        @Suppress("SwallowedException", "ThrowsCount") // Erreur typée fichier + ligne, un throw par balise.
        private fun analyserConditionnel(profondeur: Int): TemplateNode.Conditionnel {
            val ligneBalise = ligne
            index += "{{#if".length
            if (index >= source.length || !source[index].isWhitespace()) {
                throw TemplateRenderException(fichier, ligneBalise, "espace attendu après {{#if")
            }
            val finExpression = source.indexOf("}}", index)
            if (finExpression < 0) {
                throw TemplateRenderException(fichier, ligneBalise, "balise {{#if}} non fermée")
            }
            val texteExpression = source.substring(index, finExpression).trim()
            if (texteExpression.isEmpty()) {
                throw TemplateRenderException(fichier, ligneBalise, "expression vide dans un bloc {{#if}}")
            }
            val expression =
                try {
                    ExpressionParser.analyser(texteExpression)
                } catch (erreur: ExpressionException) {
                    throw TemplateRenderException(fichier, ligneBalise, "expression invalide : ${erreur.message}")
                }
            index = finExpression + "}}".length

            val (alors, premierArret) = analyserBranche(stopSurSinon = true, stopSurFin = true, profondeur + 1)
            if (premierArret == null) {
                throw TemplateRenderException(fichier, ligneBalise, "bloc {{#if}} non fermé")
            }
            var sinon = emptyList<TemplateNode>()
            if (premierArret == "{{#else}}") {
                val (brancheSinon, secondArret) =
                    analyserBranche(
                        stopSurSinon = false,
                        stopSurFin = true,
                        profondeur + 1,
                    )
                if (secondArret != "{{/if}}") {
                    throw TemplateRenderException(fichier, ligneBalise, "{{#else}} répété ou bloc {{#if}} non fermé")
                }
                sinon = brancheSinon
            }
            return TemplateNode.Conditionnel(expression, alors, sinon, ligneBalise)
        }

        /** `{{t:clé}}`. */
        private fun analyserTraduction(): TemplateNode.Traduction {
            val ligneBalise = ligne
            index += "{{t:".length
            val fin = source.indexOf("}}", index)
            if (fin < 0) {
                throw TemplateRenderException(fichier, ligneBalise, "balise {{t:…}} non fermée")
            }
            val cle = source.substring(index, fin).trim()
            if (!MOTIF_CLE_I18N.matches(cle)) {
                throw TemplateRenderException(fichier, ligneBalise, "clé i18n invalide « $cle »")
            }
            index = fin + "}}".length
            return TemplateNode.Traduction(cle, ligneBalise)
        }

        /** `{{nom}}` ou `{{nom|filtre}}` (espaces tolérés). */
        @Suppress("ThrowsCount") // Un throw par balise malformée — erreurs localisées.
        private fun analyserVariable(): TemplateNode.Variable {
            val ligneBalise = ligne
            index += OUVERTURE.length
            val fin = source.indexOf("}}", index)
            if (fin < 0) {
                throw TemplateRenderException(fichier, ligneBalise, "balise non fermée (marqueur « {{ » orphelin)")
            }
            val contenu = source.substring(index, fin).trim()
            index = fin + "}}".length
            if (contenu.isEmpty()) {
                throw TemplateRenderException(fichier, ligneBalise, "balise vide « {{}} »")
            }
            if (contenu.startsWith("#") || contenu.startsWith("/")) {
                throw TemplateRenderException(fichier, ligneBalise, "balise inconnue « $contenu »")
            }
            val parties = contenu.split("|")
            if (parties.size > 2) {
                throw TemplateRenderException(fichier, ligneBalise, "un seul filtre par balise (reçu « $contenu »)")
            }
            val nom = parties[0].trim()
            val filtre = parties.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            if (!MOTIF_IDENTIFIANT.matches(nom)) {
                throw TemplateRenderException(fichier, ligneBalise, "nom de variable invalide « $nom »")
            }
            if (filtre != null && !MOTIF_IDENTIFIANT.matches(filtre)) {
                throw TemplateRenderException(fichier, ligneBalise, "nom de filtre invalide « $filtre »")
            }
            return TemplateNode.Variable(nom, filtre, ligneBalise)
        }
    }
}
