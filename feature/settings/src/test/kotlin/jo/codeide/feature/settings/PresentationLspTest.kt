package jo.codeide.feature.settings

import jo.codeide.core.domain.CapaciteLsp
import jo.codeide.core.domain.EtatMoteurLsp
import jo.codeide.core.domain.LangageLsp
import jo.codeide.core.domain.MoteursLsp
import jo.codeide.core.domain.PhaseMoteurLsp
import jo.codeide.core.model.LspSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du présentateur PUR des écrans « Intelligence du code » (lot
 * LSP-3, spec § 5) : JVM SANS Android — les identifiants de ressource
 * sont comparés TELS QUELS (le libellé localisé se résout à l'écran).
 * Couvre TOUS les états × les TROIS langages + les paliers de réglages.
 */
class PresentationLspTest {
    // ------------------------------------------------------------------
    // L1 — Vue d'ensemble : tous les états du moteur Java
    // ------------------------------------------------------------------

    @Test
    fun `apercu - sans moteur les trois langages sont inactifs`() {
        val modele = PresentationLsp.apercu(MoteursLsp.INACTIFS)

        assertEquals(EtatMoteurAffiche.INACTIF, modele.java.etat)
        assertEquals(EtatMoteurAffiche.INACTIF, modele.kotlin.etat)
        assertEquals(EtatMoteurAffiche.INACTIF, modele.xml.etat)
        assertEquals(listOf(TexteLsp.Ressource(R.string.lsp_etat_inactif)), modele.java.sousTitres)
    }

    @Test
    fun `apercu - construction en cours porte son avancement`() {
        val modele =
            PresentationLsp.apercu(
                MoteursLsp(
                    java =
                        EtatMoteurLsp(
                            phase = PhaseMoteurLsp.CONSTRUCTION,
                            avancementPourCent = 62,
                        ),
                ),
            )

        assertEquals(EtatMoteurAffiche.EN_COURS, modele.java.etat)
        assertEquals(
            listOf(TexteLsp.Ressource(R.string.lsp_etat_indexation, listOf("62"))),
            modele.java.sousTitres,
        )
    }

    @Test
    fun `apercu - prêt sans compte dit juste prêt, avec comptes dit fichiers et avertissements`() {
        val nu = PresentationLsp.apercu(MoteursLsp(java = EtatMoteurLsp(phase = PhaseMoteurLsp.PRET)))
        assertEquals(listOf(TexteLsp.Ressource(R.string.lsp_etat_pret)), nu.java.sousTitres)

        val compte =
            PresentationLsp.apercu(
                MoteursLsp(
                    java =
                        EtatMoteurLsp(
                            phase = PhaseMoteurLsp.PRET,
                            fichiersIndexes = 191,
                            avertissements = 3,
                            horodatageDernierPassageMs = 1_700_000_000_000,
                        ),
                ),
            )
        assertEquals(
            listOf(
                TexteLsp.Ressource(R.string.lsp_etat_pret),
                TexteLsp.Pluriel(R.plurals.lsp_fichiers_indexes, 191),
                TexteLsp.Pluriel(R.plurals.lsp_avertissements, 3),
            ),
            compte.java.sousTitres,
        )
        assertEquals(1_700_000_000_000, compte.java.dernierPassageMs)
    }

    @Test
    fun `apercu - partiel et erreur portent leurs comptes`() {
        val partiel =
            PresentationLsp.apercu(
                MoteursLsp(
                    java = EtatMoteurLsp(phase = PhaseMoteurLsp.PARTIEL, fichiersIndexes = 7, avertissements = 2),
                ),
            )
        assertEquals(EtatMoteurAffiche.PARTIEL, partiel.java.etat)
        assertTrue(TexteLsp.Pluriel(R.plurals.lsp_avertissements, 2) in partiel.java.sousTitres)

        val erreur =
            PresentationLsp.apercu(
                MoteursLsp(java = EtatMoteurLsp(phase = PhaseMoteurLsp.ERREUR, erreurs = 4)),
            )
        assertEquals(EtatMoteurAffiche.ERREUR, erreur.java.etat)
        assertTrue(TexteLsp.Pluriel(R.plurals.lsp_erreurs, 4) in erreur.java.sousTitres)
    }

    @Test
    fun `apercu - Kotlin et XML ne disent que la présence de session`() {
        val modele =
            PresentationLsp.apercu(
                MoteursLsp(
                    java = EtatMoteurLsp(phase = PhaseMoteurLsp.PRET),
                    kotlin = true,
                    xml = false,
                ),
            )

        assertEquals(EtatMoteurAffiche.PRET, modele.kotlin.etat)
        assertEquals(EtatMoteurAffiche.INACTIF, modele.xml.etat)
    }

    // ------------------------------------------------------------------
    // L2 — Langage : fonctions EN ORDRE FIXE, annoncées seulement
    // ------------------------------------------------------------------

    @Test
    fun `langage - Java affiche les dix fonctions en ordre fixe`() {
        val modele =
            PresentationLsp.langage(
                LangageLsp.JAVA,
                MoteursLsp.INACTIFS,
                capacites(*CapaciteLsp.entries.toTypedArray()),
            )

        assertFalse(modele.packLangueBientot)
        assertEquals(10, modele.fonctions.size)
        assertEquals(R.string.lsp_cap_completion, modele.fonctions.first().titre)
        assertEquals(R.string.lsp_cap_indices, modele.fonctions.last().titre)
    }

    @Test
    fun `langage - Kotlin sans formatage ni indices, XML sans signatures ni références`() {
        val kotlin =
            PresentationLsp.langage(
                LangageLsp.KOTLIN,
                MoteursLsp.INACTIFS,
                capacites(
                    CapaciteLsp.COMPLETION,
                    CapaciteLsp.SURVOL,
                    CapaciteLsp.DIAGNOSTICS,
                    CapaciteLsp.SIGNATURES,
                    CapaciteLsp.DEFINITION,
                    CapaciteLsp.REFERENCES,
                    CapaciteLsp.RENOMMAGE,
                    CapaciteLsp.ACTIONS_RAPIDES,
                ),
            )
        assertEquals(8, kotlin.fonctions.size)
        assertTrue(kotlin.packLangueBientot)
        assertFalse(kotlin.fonctions.any { it.titre == R.string.lsp_cap_formatage })
        assertFalse(kotlin.fonctions.any { it.titre == R.string.lsp_cap_indices })

        val xml =
            PresentationLsp.langage(
                LangageLsp.XML,
                MoteursLsp.INACTIFS,
                capacites(
                    CapaciteLsp.COMPLETION,
                    CapaciteLsp.SURVOL,
                    CapaciteLsp.DIAGNOSTICS,
                    CapaciteLsp.DEFINITION,
                    CapaciteLsp.RENOMMAGE,
                    CapaciteLsp.ACTIONS_RAPIDES,
                    CapaciteLsp.FORMATAGE,
                ),
            )
        assertEquals(7, xml.fonctions.size)
        assertTrue(xml.packLangueBientot)
        assertFalse(xml.fonctions.any { it.titre == R.string.lsp_cap_signatures })
        assertFalse(xml.fonctions.any { it.titre == R.string.lsp_cap_references })
        assertTrue(xml.fonctions.any { it.titre == R.string.lsp_cap_formatage })
    }

    @Test
    fun `langage - l'ordre est FIXE même si l'ensemble arrive dans le désordre`() {
        val desordonnees =
            capacites(
                CapaciteLsp.INDICES_EN_LIGNE,
                CapaciteLsp.COMPLETION,
            )
        val modele =
            PresentationLsp.langage(
                LangageLsp.JAVA,
                MoteursLsp.INACTIFS,
                desordonnees,
            )

        assertEquals(R.string.lsp_cap_completion, modele.fonctions.first().titre)
        assertEquals(R.string.lsp_cap_indices, modele.fonctions.last().titre)
    }

    @Test
    fun `langage - l'état suit le langage (Java détaillé, Kotlin présence)`() {
        val java =
            PresentationLsp.langage(
                LangageLsp.JAVA,
                MoteursLsp(java = EtatMoteurLsp(phase = PhaseMoteurLsp.CONSTRUCTION, avancementPourCent = 40)),
                capacites(),
            )
        assertEquals(EtatMoteurAffiche.EN_COURS, java.etat.etat)

        val kotlin =
            PresentationLsp.langage(
                LangageLsp.KOTLIN,
                MoteursLsp(kotlin = true),
                capacites(),
            )
        assertEquals(EtatMoteurAffiche.PRET, kotlin.etat.etat)
    }

    // ------------------------------------------------------------------
    // L3 — Index et cache : paliers, surveillance, actions
    // ------------------------------------------------------------------

    @Test
    fun `index - sans moteur le sous-titre dit inactif et les actions sont posées mais bloquées`() {
        val modele =
            PresentationLsp.index(
                MoteursLsp.INACTIFS,
                tailleCacheOctets = null,
                reglages = LspSettings.parDefaut(),
            )

        assertEquals(listOf(TexteLsp.Ressource(R.string.lsp_etat_inactif)), modele.sousTitreCache)
        assertTrue(modele.moteurAbsent)
        assertFalse(modele.vidageIndisponible)
        assertFalse(modele.echecsPresents)
    }

    @Test
    fun `index - cache mesuré, avertissements et durée composent le sous-titre`() {
        val modele =
            PresentationLsp.index(
                MoteursLsp(
                    java =
                        EtatMoteurLsp(
                            phase = PhaseMoteurLsp.PRET,
                            avertissements = 3,
                            dureeDernierPassageMs = 72_000,
                        ),
                ),
                tailleCacheOctets = 184 * 1024 * 1024L,
                reglages = LspSettings.parDefaut(),
            )

        // 184,0 Mo · 3 avertissements · 1 min 12 s — l'ordre est porté par
        // la liste, la jonction par l'écran.
        assertEquals(3, modele.sousTitreCache.size)
        assertTrue(TexteLsp.Brut("1 min 12 s") in modele.sousTitreCache)
        assertFalse(modele.moteurAbsent)
    }

    @Test
    fun `index - le budget affiche Auto puis les paliers en Mio`() {
        val auto =
            PresentationLsp.index(
                MoteursLsp.INACTIFS,
                null,
                LspSettings(budgetCacheOctets = null),
            )
        assertEquals(TexteLsp.Ressource(R.string.lsp_budget_auto), auto.valeurBudget)

        val palier =
            PresentationLsp.index(
                MoteursLsp.INACTIFS,
                null,
                LspSettings(budgetCacheOctets = 256L * 1024 * 1024),
            )
        assertEquals(
            TexteLsp.Ressource(R.string.lsp_budget_mio, listOf("256")),
            palier.valeurBudget,
        )
    }

    @Test
    fun `index - surveillance coupée puis paliers, vidage bloqué pendant une construction`() {
        val coupee =
            PresentationLsp.index(
                MoteursLsp.INACTIFS,
                null,
                LspSettings(surveillanceFichiersMs = 0),
            )
        assertFalse(coupee.surveillanceActive)
        assertNull(coupee.valeurFrequence)

        val cinqSecondes =
            PresentationLsp.index(
                MoteursLsp.INACTIFS,
                null,
                LspSettings(surveillanceFichiersMs = 5_000),
            )
        assertTrue(cinqSecondes.surveillanceActive)
        assertEquals(
            TexteLsp.Ressource(R.string.lsp_frequence_secondes, listOf("5")),
            cinqSecondes.valeurFrequence,
        )

        val pendantIndexation =
            PresentationLsp.index(
                MoteursLsp(java = EtatMoteurLsp(phase = PhaseMoteurLsp.CONSTRUCTION)),
                null,
                LspSettings.parDefaut(),
            )
        assertTrue(pendantIndexation.vidageIndisponible)
    }

    // ------------------------------------------------------------------
    // L4 — Environnement : valeurs lues, échec JDK en donnée
    // ------------------------------------------------------------------

    @Test
    fun `environnement - JDK présent, plateforme API 34, mode par module`() {
        val modele =
            PresentationLsp.environnement(
                versionJdk = "17",
                niveauApiPlateforme = 34,
                androidJarPresent = true,
            )

        assertFalse(modele.jdkAbsent)
        assertEquals("17", modele.versionJdk)
        assertEquals(TexteLsp.Ressource(R.string.lsp_api_niveau, listOf("34")), modele.plateforme)
        assertFalse(modele.modeInstantane)
    }

    @Test
    fun `environnement - JDK absent porte l'échec, sans plateforme le mode est instantané`() {
        val modele =
            PresentationLsp.environnement(
                versionJdk = null,
                niveauApiPlateforme = null,
                androidJarPresent = false,
            )

        assertTrue(modele.jdkAbsent)
        assertNull(modele.versionJdk)
        assertNull(modele.plateforme)
        assertTrue(modele.modeInstantane)
    }

    // ------------------------------------------------------------------
    // L5 — État : erreurs récentes tronquées, horodatage porté
    // ------------------------------------------------------------------

    @Test
    fun `etat - erreurs récentes du Java, tronquées, avec l'horodatage du dernier passage`() {
        val modele =
            PresentationLsp.etat(
                MoteursLsp(
                    java =
                        EtatMoteurLsp(
                            phase = PhaseMoteurLsp.PARTIEL,
                            messagesErreur = List(25) { "erreur $it" },
                            horodatageDernierPassageMs = 1_700_000_000_000,
                        ),
                ),
            )

        assertEquals(20, modele.erreursRecentes.size)
        assertEquals("erreur 19", modele.erreursRecentes.last().message)
        assertEquals(1_700_000_000_000, modele.erreursRecentes.first().horodatageMs)
        assertEquals(EtatMoteurAffiche.PARTIEL, modele.java.etat)
    }

    @Test
    fun `etat - sans moteur le résumé est inactif et aucune erreur`() {
        val modele = PresentationLsp.etat(MoteursLsp.INACTIFS)

        assertEquals(EtatMoteurAffiche.INACTIF, modele.java.etat)
        assertTrue(modele.erreursRecentes.isEmpty())
    }

    private fun capacites(vararg valeurs: CapaciteLsp) = valeurs.toSet()
}
