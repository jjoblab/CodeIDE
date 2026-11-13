package jo.codeide.feature.settings

import jo.codeide.core.domain.CapaciteLsp
import jo.codeide.core.domain.MoteursLsp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test d'ARCHITECTURE du lot LSP-4 (ADR 0128 action 5, spec LSP § 5) :
 * « **aucun interrupteur sans capacité annoncée** » — les rangées de
 * fonctions de l'écran L2 (les interrupteurs dès que les filtres côté
 * client auront un effet, décision LSP-2) sont DÉRIVÉES de la table des
 * capacités annoncées, jamais codées en dur : ce test verrouille la
 * DÉRIVATION pour TOUTE table — l'écran suit la table, y compris demain
 * (un pack de langue qui annonce moins n'affiche pas plus, un moteur
 * qui annonce plus obtient ses rangées sans toucher l'écran).
 *
 * Le miroir des capacités RÉELLES du frère (Java 10, Kotlin 8 sans
 * formatage ni indices, XML 7 sans signatures ni références) est
 * verrouillé par `PortLspEspaceTest` côté `:feature:editor` — c'est LUI
 * qui alimente la table en production ; « ∩ consommables » (spec § 5)
 * est porté par l'énumération elle-même (`CapaciteLsp` ne contient QUE
 * des capacités réglables consommables par l'éditeur, ADR 0128 §3).
 */
class ArchitectureInterrupteursLspTest {
    // ------------------------------------------------------------------
    // Dérivation : rangées = annoncées ∩ ordre FIXE de la spécification
    // ------------------------------------------------------------------

    @Test
    fun `les rangées suivent exactement les capacités annoncées - les dix, en ordre fixe`() {
        val modele =
            PresentationLsp.langage(
                langage = jo.codeide.core.domain.LangageLsp.JAVA,
                moteurs = MoteursLsp.INACTIFS,
                capacitesAnnoncees = CapaciteLsp.entries.toSet(),
            )

        // Ordre de la spécification § 3 L2 (interrompu JAMAIS par l'ordre
        // de la table, d'un set ni d'une énumération).
        assertEquals(
            listOf(
                R.string.lsp_cap_completion,
                R.string.lsp_cap_survol,
                R.string.lsp_cap_diagnostics,
                R.string.lsp_cap_signatures,
                R.string.lsp_cap_definition,
                R.string.lsp_cap_references,
                R.string.lsp_cap_renommage,
                R.string.lsp_cap_actions,
                R.string.lsp_cap_formatage,
                R.string.lsp_cap_indices,
            ),
            modele.fonctions.map { it.titre },
        )
        // Chaque rangée porte SON effet en une phrase (une ressource par
        // capacité, ADR 0117 — jamais un libellé partagé).
        assertEquals(
            listOf(
                R.string.lsp_cap_completion_desc,
                R.string.lsp_cap_survol_desc,
                R.string.lsp_cap_diagnostics_desc,
                R.string.lsp_cap_signatures_desc,
                R.string.lsp_cap_definition_desc,
                R.string.lsp_cap_references_desc,
                R.string.lsp_cap_renommage_desc,
                R.string.lsp_cap_actions_desc,
                R.string.lsp_cap_formatage_desc,
                R.string.lsp_cap_indices_desc,
            ),
            modele.fonctions.map { it.description },
        )
    }

    @Test
    fun `une table qui n'annonce rien n'affiche AUCUNE rangée`() {
        LangagePourTest.entries.forEach { langage ->
            val modele =
                PresentationLsp.langage(
                    langage = langage.langage,
                    moteurs = MoteursLsp.INACTIFS,
                    capacitesAnnoncees = emptySet(),
                )

            assertEquals("aucune rangée pour $langage", 0, modele.fonctions.size)
        }
    }

    @Test
    fun `l'ordre vient de la spécification, jamais de la table`() {
        // Annoncées « en vrac » (set, ordre d'énumération inversé) :
        // les rangées restent dans l'ordre FIXE.
        val modele =
            PresentationLsp.langage(
                langage = jo.codeide.core.domain.LangageLsp.KOTLIN,
                moteurs = MoteursLsp.INACTIFS,
                capacitesAnnoncees =
                setOf(
                    CapaciteLsp.INDICES_EN_LIGNE, // hors Kotlin réel : le test est générique
                    CapaciteLsp.FORMATAGE,
                    CapaciteLsp.COMPLETION,
                ),
            )

        assertEquals(
            listOf(
                R.string.lsp_cap_completion,
                R.string.lsp_cap_formatage,
                R.string.lsp_cap_indices,
            ),
            modele.fonctions.map { it.titre },
        )
    }

    @Test
    fun `aucune rangée pour une capacité non annoncée - l'écran suit la table, pas l'inverse`() {
        // Une table qui annonce MOINS qu'avant (pack de langue réduit) :
        // les rangées disparaissent — jamais un interrupteur orphelin.
        val annoncees =
            setOf(
                CapaciteLsp.SURVOL,
                CapaciteLsp.DEFINITION,
                CapaciteLsp.ACTIONS_RAPIDES,
            )
        val modele =
            PresentationLsp.langage(
                langage = jo.codeide.core.domain.LangageLsp.XML,
                moteurs = MoteursLsp.INACTIFS,
                capacitesAnnoncees = annoncees,
            )

        assertEquals(annoncees.size, modele.fonctions.size)
        assertEquals(
            annoncees.size,
            modele.fonctions.count { rangee ->
                rangee.titre in
                    listOf(
                        R.string.lsp_cap_survol,
                        R.string.lsp_cap_definition,
                        R.string.lsp_cap_actions,
                    )
            },
        )
    }

    @Test
    fun `un pack de langue qui annonce plus obtient ses rangées sans toucher l'écran`() {
        // Demain : le frère annonce TOUT pour les trois langages — l'écran
        // suit mécaniquement (le présentateur ne connaît aucun langage).
        LangagePourTest.entries.forEach { langage ->
            val modele =
                PresentationLsp.langage(
                    langage = langage.langage,
                    moteurs = MoteursLsp.INACTIFS,
                    capacitesAnnoncees = CapaciteLsp.entries.toSet(),
                )

            assertEquals("toutes les rangées pour $langage", CapaciteLsp.entries.size, modele.fonctions.size)
        }
    }

    /** Les trois langages, sans dépondre du domaine dans les boucles. */
    private enum class LangagePourTest(val langage: jo.codeide.core.domain.LangageLsp) {
        JAVA(jo.codeide.core.domain.LangageLsp.JAVA),
        KOTLIN(jo.codeide.core.domain.LangageLsp.KOTLIN),
        XML(jo.codeide.core.domain.LangageLsp.XML),
    }
}
