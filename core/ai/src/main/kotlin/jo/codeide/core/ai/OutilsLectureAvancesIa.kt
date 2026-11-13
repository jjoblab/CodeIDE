package jo.codeide.core.ai

import jo.codeide.core.domain.DiagnosticEditeur
import jo.codeide.core.model.AppResult

/**
 * Port des diagnostics du fichier ACTIF (rapport IA, chantier IA-CODEIDE,
 * lot 2, A5) : l'éditeur expose les diagnostics du fichier ouvert — le
 * port vit dans `core:ai`, l'implémentation (liée par Hilt côté tiroir IA)
 * lit le [jo.codeide.core.domain.PortEditeurActif] publié par l'éditeur.
 *
 * Les diagnostics d'un fichier NON ouvert ne sont pas exigés : la donnée
 * porte le chemin actif — l'outil formule le message honnête.
 */
public fun interface SourceDiagnosticsFichierIa {
    /**
     * Diagnostics du fichier ACTIF de l'éditeur.
     *
     * @return le chemin actif (ou `null` : aucun éditeur) et ses
     * diagnostics (vides si aucun).
     */
    public fun diagnostics(): DiagnosticsFichierIa
}

/** Diagnostics du fichier actif (A5, rapport IA lot 2). */
public data class DiagnosticsFichierIa(
    /** Chemin relatif du fichier actif, ou `null` (aucun onglet). */
    public val cheminActif: String?,
    /** Diagnostics visibles du fichier actif. */
    public val diagnostics: List<DiagnosticEditeur> = emptyList(),
)

/**
 * Outil `search_text` (rapport IA, lot 2, A5) : recherche LITTÉRALE
 * bornée dans le projet — LECTURE, aucune approbation. Le motif vide est
 * refusé (l'outil valide ses entrées, ADR 0125).
 */
public class OutilRechercherTexteIa(private val port: FournisseurContexteProjet) : OutilIa {
    override val identifiant: String = "search_text"
    override val description: String =
        "Recherche un texte littéral dans le projet (occurrences bornées, avec ligne et extrait)."
    override val niveau: NiveauRisqueIa = NiveauRisqueIa.LECTURE

    override val parametresJson: String =
        """
        {"type":"object",
         "properties":{"motif":{"type":"string"},"limite":{"type":"string"}},
         "required":["motif"]}
        """.trimIndent()

    @Suppress("ReturnCount") // refus de garde (motif vide) + succès/échec du port : sorties distinctes (ADR 0125).
    override suspend fun executer(arguments: Map<String, String>): ResultatOutilIa {
        val motif = arguments["motif"].orEmpty()
        if (motif.isBlank()) {
            return ResultatOutilIa.Refuse("motif vide — donne le texte à chercher")
        }
        val limite = arguments["limite"]?.trim()?.toIntOrNull()?.coerceIn(1, OCCURRENCES_MAX) ?: OCCURRENCES_DEFAUT
        return when (val resultat = port.rechercherTexte(motif, limite)) {
            is AppResult.Success -> {
                val occurrences = resultat.value
                if (occurrences.isEmpty()) {
                    ResultatOutilIa.Succes("aucune occurrence de « $motif »")
                } else {
                    ResultatOutilIa.Succes(
                        occurrences.joinToString("\n") { occurrence ->
                            "${occurrence.cheminRelatif}:${occurrence.ligne}: ${occurrence.extrait}"
                        },
                    )
                }
            }

            is AppResult.Failure -> ResultatOutilIa.Refuse("recherche indisponible (projet ou lecture)")
        }
    }

    private companion object {
        /** Plafond dur d'occurrences par appel (sobriété du contexte). */
        private const val OCCURRENCES_MAX = 50

        /** Défaut d'occurrences quand le modèle ne borne pas. */
        private const val OCCURRENCES_DEFAUT = 20
    }
}

/**
 * Outil `read_lines` (rapport IA, lot 2, A5) : lecture d'un INTERVALLE
 * de lignes (1-based inclus) — LECTURE, aucune approbation. Les bornes
 * invalides sont refusées ; les bornes valides sont REPLIÉES par le port
 * (plafond de lignes par appel).
 */
public class OutilLireLignesIa(private val port: FournisseurContexteProjet) : OutilIa {
    override val identifiant: String = "read_lines"
    override val description: String = "Lit un intervalle de lignes d'un fichier (1-based, inclus)."
    override val niveau: NiveauRisqueIa = NiveauRisqueIa.LECTURE

    override val parametresJson: String =
        """
        {"type":"object",
         "properties":{"chemin":{"type":"string"},"debut":{"type":"string"},"fin":{"type":"string"}},
         "required":["chemin","debut","fin"]}
        """.trimIndent()

    @Suppress("ReturnCount") // refus de garde (chemin, bornes) + succès/échec du port : sorties distinctes (ADR 0125).
    override suspend fun executer(arguments: Map<String, String>): ResultatOutilIa {
        val chemin = arguments["chemin"].orEmpty()
        if (chemin.isBlank()) {
            return ResultatOutilIa.Refuse("chemin manquant")
        }
        val debut = arguments["debut"]?.trim()?.toIntOrNull()
        val fin = arguments["fin"]?.trim()?.toIntOrNull()
        if (debut == null || fin == null) {
            return ResultatOutilIa.Refuse("bornes invalides : donne debut et fin en nombres (1-based, inclus)")
        }
        if (debut < 1 || fin < debut) {
            return ResultatOutilIa.Refuse("bornes invalides : donne debut >= 1 et fin >= debut (1-based, inclus)")
        }
        return when (val resultat = port.lireLignes(chemin, debut, fin)) {
            is AppResult.Success -> ResultatOutilIa.Succes(resultat.value)

            is AppResult.Failure -> ResultatOutilIa.Refuse(
                "lecture impossible : $chemin (fichier absent, exclu ou plage hors limites)",
            )
        }
    }
}

/**
 * Outil `diagnostics_lsp` (rapport IA, lot 2, A5) : problèmes du fichier
 * ACTIF de l'éditeur (parseurs et LSP) — LECTURE, aucune approbation.
 * Les diagnostics ne sont exigés QUE pour le fichier ouvert : une demande
 * sur un AUTRE fichier reçoit le message honnête (ouvre-le d'abord).
 */
public class OutilDiagnosticsFichierIa(private val source: SourceDiagnosticsFichierIa) : OutilIa {
    override val identifiant: String = "diagnostics_lsp"
    override val description: String =
        "Liste les diagnostics (erreurs, avertissements) du fichier ouvert dans l'éditeur."
    override val niveau: NiveauRisqueIa = NiveauRisqueIa.LECTURE

    override val parametresJson: String =
        """
        {"type":"object",
         "properties":{"chemin":{"type":"string"}}}
        """.trimIndent()

    @Suppress("ReturnCount") // garde SÉQUENTIELLE : un retour par cas honnête (ADR 0125).
    override suspend fun executer(arguments: Map<String, String>): ResultatOutilIa {
        val demande = arguments["chemin"]?.trim()?.takeIf { it.isNotEmpty() }
        val donnees = source.diagnostics()
        val actif = donnees.cheminActif
        if (actif == null) {
            return ResultatOutilIa.Refuse("aucun fichier ouvert dans l'éditeur — ouvre un fichier puis rappelle")
        }
        if (demande != null && demande != actif) {
            return ResultatOutilIa.Refuse(
                "diagnostics disponibles uniquement pour le fichier ouvert : $actif (demandé : $demande)",
            )
        }
        if (donnees.diagnostics.isEmpty()) {
            return ResultatOutilIa.Succes("aucun diagnostic pour $actif")
        }
        return ResultatOutilIa.Succes(
            donnees.diagnostics.joinToString("\n") { diagnostic ->
                "${diagnostic.gravite.name} ${diagnostic.cheminRelatif}:" +
                    "${diagnostic.ligne}:${diagnostic.colonne} ${diagnostic.message}"
            },
        )
    }
}
