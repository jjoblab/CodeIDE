package jo.codeide.preview.compose

import jo.lspkotlin.api.AppelFonctionKotlin
import jo.lspkotlin.api.ArgumentAppelKotlin
import jo.lspkotlin.api.ExpressionNonInterpreteeKotlin
import jo.lspkotlin.api.GenreArgumentAppelKotlin
import jo.lspkotlin.api.GenreInlineKotlin
import jo.lspkotlin.api.NoeudAppelKotlin
import jo.lspkotlin.api.PreviewKotlin

/**
 * Conversion de l'arbre d'appels NEUTRE du LSP Kotlin en PROGRAMME
 * renderable (C1) — la partie INTERPRÉTATION de la voie interpréteur
 * (PC1) qui vit côté APP : lecture des littéraux (chaîne sans
 * guillemets, entier, booléen, valeur dp), splicing des inlines du
 * projet (le wrapper disparaît, son corps marche prend la place),
 * pastilles d'erreur TYPIQUES pour tout le reste (jamais un faux
 * rendu — PC1), et DOUBLE budget (défense en profondeur : le moteur
 * LSP a déjà borné la marche, le programme est borné à nouveau ici).
 */
internal object ConvertisseurProgramme {
    /** Budget TOTAL de nœuds du programme (défense en profondeur, miroir moteur). */
    internal const val BUDGET_NOEUDS: Int = 500

    /** Le registre des composables que le moteur sait EXÉCUTER (C1). */
    private val REGISTRE: Map<String, GenreNoeudCompose> =
        mapOf(
            "Text" to GenreNoeudCompose.TEXTE,
            "Column" to GenreNoeudCompose.COLONNE,
            "Row" to GenreNoeudCompose.RANGEE,
            "Box" to GenreNoeudCompose.BOITE,
            "Spacer" to GenreNoeudCompose.ESPACE,
            "Button" to GenreNoeudCompose.BOUTON,
        )

    /**
     * Convertit le corps d'une [PreviewKotlin] en programme renderable
     * + le catalogue des erreurs typées rencontrées.
     */
    internal fun convertir(preview: PreviewKotlin): Pair<ProgrammeCompose, List<ErreurCompose>> {
        val erreurs = mutableListOf<ErreurCompose>()
        val budget = Budget()
        val racine = convertirNoeuds(preview.corps, budget, erreurs)
        val coupure = preview.coupureBudget || budget.eteint
        if (coupure) {
            erreurs += ErreurCompose(GenreErreurCompose.BUDGET_INTERPRETATION, null, 0)
        }
        return ProgrammeCompose(racine, coupure, ProgrammeCompose.digesteDe(racine, coupure)) to erreurs
    }

    /** Convertit une liste de nœuds neutres (profondeur protégée par le budget). */
    private fun convertirNoeuds(
        noeuds: List<NoeudAppelKotlin>,
        budget: Budget,
        erreurs: MutableList<ErreurCompose>,
    ): List<NoeudProgramme> {
        val resultat = mutableListOf<NoeudProgramme>()
        for (noeud in noeuds) {
            if (budget.eteint) break
            when (noeud) {
                is AppelFonctionKotlin -> ajouterAppel(noeud, resultat, budget, erreurs)

                is ExpressionNonInterpreteeKotlin -> {
                    budget.consommer()
                    erreurs += ErreurCompose(GenreErreurCompose.EXPRESSION_NON_INTERPRETEE, noeud.texte, noeud.ligne)
                    resultat.add(
                        NoeudProgramme(
                            genre = GenreNoeudCompose.ERREUR_EXPRESSION,
                            nom = noeud.texte,
                            ligne = noeud.ligne,
                            arguments = emptyList(),
                            contenu = emptyList(),
                        ),
                    )
                }
            }
        }
        return resultat
    }

    /**
     * Ajoute UN appel à la séquence : inline RÉUSSI → le wrapper est
     * TRANSPARENT, son corps marché prend la PLACE de l'appel
     * (splicing) ; sinon l'appel converti tel quel.
     */
    private fun ajouterAppel(
        appel: AppelFonctionKotlin,
        resultat: MutableList<NoeudProgramme>,
        budget: Budget,
        erreurs: MutableList<ErreurCompose>,
    ) {
        if (appel.genreInline == GenreInlineKotlin.PROJET_INLINE) {
            resultat.addAll(convertirNoeuds(appel.contenu, budget, erreurs))
        } else {
            convertirAppel(appel, budget, erreurs)?.let { resultat.add(it) }
        }
    }

    /**
     * Convertit UN appel NON inline : registre → genre renderable,
     * inconnu → pastille [GenreNoeudCompose.INCONNU], inline échoué →
     * pastille [GenreNoeudCompose.ERREUR_INLINE] typée.
     */
    private fun convertirAppel(
        appel: AppelFonctionKotlin,
        budget: Budget,
        erreurs: MutableList<ErreurCompose>,
    ): NoeudProgramme? {
        budget.consommer()
        val arguments = appel.arguments.map { argument -> convertirArgument(argument, erreurs, appel) }
        val genre =
            when (appel.genreInline) {
                GenreInlineKotlin.PROJET_CYCLE -> {
                    erreurs += ErreurCompose(GenreErreurCompose.COMPOSABLE_PROJET_CYCLE, appel.nom, appel.ligne)
                    GenreNoeudCompose.ERREUR_INLINE
                }

                GenreInlineKotlin.PROJET_BUDGET -> {
                    erreurs += ErreurCompose(GenreErreurCompose.COMPOSABLE_PROJET_BUDGET, appel.nom, appel.ligne)
                    GenreNoeudCompose.ERREUR_INLINE
                }

                GenreInlineKotlin.PROJET_PARAMETRES -> {
                    erreurs += ErreurCompose(GenreErreurCompose.COMPOSABLE_PROJET_PARAMETRES, appel.nom, appel.ligne)
                    GenreNoeudCompose.ERREUR_INLINE
                }

                GenreInlineKotlin.PROJET_ILLISIBLE -> {
                    erreurs += ErreurCompose(GenreErreurCompose.COMPOSABLE_PROJET_ILLISIBLE, appel.nom, appel.ligne)
                    GenreNoeudCompose.ERREUR_INLINE
                }

                GenreInlineKotlin.AUCUN -> {
                    REGISTRE[appel.nom]
                        ?: run {
                            erreurs += ErreurCompose(GenreErreurCompose.COMPOSABLE_INCONNU, appel.nom, appel.ligne)
                            GenreNoeudCompose.INCONNU
                        }
                }

                // L'inline RÉUSSI ne passe JAMAIS ici (splicing amont) —
                // branche défensive pour l'exhaustivité du when.
                GenreInlineKotlin.PROJET_INLINE -> {
                    GenreNoeudCompose.INCONNU
                }
            }
        return NoeudProgramme(
            genre = genre,
            nom = appel.nom,
            ligne = appel.ligne,
            arguments = arguments,
            contenu = convertirNoeuds(appel.contenu, budget, erreurs),
        )
    }

    /** Convertit UN argument : seuls les littéraux et `N.dp` traversent. */
    private fun convertirArgument(
        argument: ArgumentAppelKotlin,
        erreurs: MutableList<ErreurCompose>,
        appel: AppelFonctionKotlin,
    ): ArgumentProgramme {
        val texte = argument.texte
        val genreEtValeur: Pair<GenreArgumentCompose, String?>? =
            when (argument.genre) {
                GenreArgumentAppelKotlin.LITTERAL -> litteralDe(texte)
                GenreArgumentAppelKotlin.REFERENCE -> dpDe(texte)
                GenreArgumentAppelKotlin.AUTRE -> null
            }
        if (genreEtValeur == null) {
            erreurs += ErreurCompose(GenreErreurCompose.ARGUMENT_NON_INTERPRETABLE, argument.nom ?: texte, appel.ligne)
        }
        val (genre, valeur) = genreEtValeur ?: GenreArgumentCompose.NON_INTERPRETE to null
        return ArgumentProgramme(nom = argument.nom, genre = genre, valeur = valeur)
    }

    /** Un littéral : chaîne (guillemets retirés), entier, booléen — sinon rien. */
    private fun litteralDe(texte: String?): Pair<GenreArgumentCompose, String?>? = when {
        texte == null -> null

        texte.length >= 2 && texte.startsWith("\"") && texte.endsWith("\"") ->
            GenreArgumentCompose.LITTERAL_CHAINE to texte.substring(1, texte.length - 1)

        texte.toIntOrNull() != null -> GenreArgumentCompose.LITTERAL_ENTIER to texte

        texte == "true" || texte == "false" -> GenreArgumentCompose.LITTERAL_BOOLEEN to texte

        else -> null
    }

    /**
     * Une valeur dp écrite `N.dp` / `N.f.dp` : le NOMBRE, sans unité —
     * pipeline (trim → suffixe → nombre VALIDE ; `toFloatOrNull` couvre
     * les entiers ET les décimaux).
     */
    internal fun dpDe(texte: String?): Pair<GenreArgumentCompose, String?>? = texte
        ?.trim()
        ?.takeIf { it.endsWith(".dp") }
        ?.removeSuffix(".dp")
        ?.takeIf { nombre -> nombre.toFloatOrNull() != null }
        ?.let { GenreArgumentCompose.VALEUR_DP to it }

    /** Budget du programme (défense en profondeur). */
    private class Budget {
        private var restant: Int = BUDGET_NOEUDS

        val eteint: Boolean
            get() = restant <= 0

        fun consommer() {
            restant--
        }
    }
}
