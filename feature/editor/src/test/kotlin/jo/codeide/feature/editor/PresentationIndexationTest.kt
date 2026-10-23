package jo.codeide.feature.editor

import jo.codeide.lsp.host.BilanDernierPassageIndexation
import jo.codeide.lsp.host.EntreeTravailIndexation
import jo.codeide.lsp.host.EtatEntreeTravailIndexation
import jo.codeide.lsp.host.EtatIndexation
import jo.codeide.lsp.host.PhaseIndexation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests purs du présentateur [PresentationIndexation] (Phase B prompt 2,
 * ADR 0117 §4.4 — lot B4). Valide tous les états de l'indexation ×
 * pastille + panneau de détail. Convention : noms en français entre
 * accents graves (ADR 0007).
 */
class PresentationIndexationTest {
    // ============================================================
    // État INITIAL
    // ============================================================

    @Test
    fun `etat INITIAL — pastille masquee etroit, pas de pourcentage`() {
        val modele = PresentationIndexation.afficher(EtatIndexation.INITIAL)
        assertEquals(EtatPastilleIndexation.INITIAL, modele.pastille.etat)
        assertNull(modele.pastille.pourcentage)
        assertTrue(modele.pastille.masqueeEtroit)
    }

    @Test
    fun `etat INITIAL — panneau sans liste de travail, sans moteurs, sans bilan`() {
        val modele = PresentationIndexation.afficher(EtatIndexation.INITIAL)
        assertNull(modele.panneau.listeTravail)
        assertNull(modele.panneau.moteurs)
        assertNull(modele.panneau.bilan)
        assertTrue(modele.panneau.actions.isEmpty())
        assertNull(modele.panneau.noteImpact)
    }

    // ============================================================
    // État CONSTRUCTION
    // ============================================================

    @Test
    fun `etat CONSTRUCTION avec total — pastille avec pourcentage`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 119,
                total = 191,
                entreeCourante = "material 1.14.0",
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertEquals(EtatPastilleIndexation.CONSTRUCTION, modele.pastille.etat)
        // 119/191 = 62.3% → arrondi à 62
        assertEquals(62, modele.pastille.pourcentage)
        assertFalse(modele.pastille.masqueeEtroit)
    }

    @Test
    fun `etat CONSTRUCTION total inconnu — pastille sans pourcentage`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 0,
                total = 0,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertEquals(EtatPastilleIndexation.CONSTRUCTION, modele.pastille.etat)
        assertNull(modele.pastille.pourcentage)
    }

    @Test
    fun `etat CONSTRUCTION — progression determinee avec compteur`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 118,
                total = 191,
                entreeCourante = "material 1.14.0",
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertFalse(modele.panneau.progression.indeterminee)
        assertEquals(118f / 191f, modele.panneau.progression.fraction, 0.001f)
        assertNotNull(modele.panneau.progression.compteur)
    }

    @Test
    fun `etat CONSTRUCTION total inconnu — progression indeterminee`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 0,
                total = 0,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertTrue(modele.panneau.progression.indeterminee)
        assertNull(modele.panneau.progression.compteur)
    }

    @Test
    fun `etat CONSTRUCTION avec liste de travail — panneau affiche la liste`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 2,
                total = 3,
                entreeCourante = "material 1.14.0",
                avertissements = emptyList(),
                erreurs = emptyList(),
                listeTravail =
                listOf(
                    EntreeTravailIndexation("androidx.core 1.19.0", EtatEntreeTravailIndexation.REPRIS),
                    EntreeTravailIndexation("material 1.14.0", EtatEntreeTravailIndexation.ACTIF),
                    EntreeTravailIndexation("okio 3.9.0", EtatEntreeTravailIndexation.EN_ATTENTE),
                ),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertNotNull(modele.panneau.listeTravail)
        assertEquals(3, modele.panneau.listeTravail!!.size)
        assertEquals(EtatEntreeDetail.REPRIS, modele.panneau.listeTravail!![0].etat)
        assertEquals(EtatEntreeDetail.ACTIF, modele.panneau.listeTravail!![1].etat)
        assertEquals(EtatEntreeDetail.EN_ATTENTE, modele.panneau.listeTravail!![2].etat)
    }

    @Test
    fun `etat CONSTRUCTION — note d impact info affichee`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 0,
                total = 100,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertNotNull(modele.panneau.noteImpact)
        assertEquals(NiveauNoteImpact.INFO, modele.panneau.noteImpact!!.niveau)
    }

    @Test
    fun `etat CONSTRUCTION — action annuler proposee`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 0,
                total = 100,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertEquals(listOf(ActionDetailIndexation.ANNULER), modele.panneau.actions)
    }

    // ============================================================
    // État PRET
    // ============================================================

    @Test
    fun `etat PRET — pastille coche masquee etroit`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.PRET,
                faits = 191,
                total = 191,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertEquals(EtatPastilleIndexation.PRET, modele.pastille.etat)
        assertNull(modele.pastille.pourcentage)
        assertTrue(modele.pastille.masqueeEtroit)
    }

    @Test
    fun `etat PRET — action reindexer proposee`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.PRET,
                faits = 191,
                total = 191,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertEquals(listOf(ActionDetailIndexation.REINDEXER), modele.panneau.actions)
    }

    @Test
    fun `etat PRET avec bilan — panneau affiche le bilan`() {
        val bilan =
            BilanDernierPassageIndexation(
                dureeMs = 4_200,
                artefactsTotal = 191,
                artefactsRepris = 188,
                artefactsReconstruits = 3,
                fichiersParcourus = 214,
                fichiersReanalyses = 12,
                indexeurPlusCouteux = "noms-classes-java",
                horodatageMs = 1_726_000_000_000,
            )
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.PRET,
                faits = 191,
                total = 191,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
                bilanDernierPassage = bilan,
                settled = true,
            )
        val modele = PresentationIndexation.afficher(etat)
        assertNotNull(modele.panneau.bilan)
        assertEquals(4_200, modele.panneau.bilan!!.dureeMs)
        assertEquals(191, modele.panneau.bilan!!.artefactsTotal)
        assertEquals(188, modele.panneau.bilan!!.artefactsRepris)
        assertEquals(3, modele.panneau.bilan!!.artefactsReconstruits)
        assertEquals(214, modele.panneau.bilan!!.fichiersParcourus)
        assertEquals(12, modele.panneau.bilan!!.fichiersReanalyses)
        assertEquals("noms-classes-java", modele.panneau.bilan!!.indexeurPlusCouteux)
    }

    @Test
    fun `etat PRET — pas de note d impact`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.PRET,
                faits = 191,
                total = 191,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertNull(modele.panneau.noteImpact)
    }

    // ============================================================
    // État PARTIEL
    // ============================================================

    @Test
    fun `etat PARTIEL — pastille avertissement visible`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.PARTIEL,
                faits = 188,
                total = 191,
                entreeCourante = null,
                avertissements = listOf("legacy-utils.jar : jar illisible"),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertEquals(EtatPastilleIndexation.PARTIEL, modele.pastille.etat)
        assertFalse(modele.pastille.masqueeEtroit)
    }

    @Test
    fun `etat PARTIEL avec avertissements — actions reessayer + reindexer`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.PARTIEL,
                faits = 188,
                total = 191,
                entreeCourante = null,
                avertissements = listOf("legacy-utils.jar : jar illisible"),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertEquals(
            listOf(ActionDetailIndexation.REESSAYER_ECHECS, ActionDetailIndexation.REINDEXER),
            modele.panneau.actions,
        )
    }

    @Test
    fun `etat PARTIEL avec avertissements — note d impact avertissement`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.PARTIEL,
                faits = 188,
                total = 191,
                entreeCourante = null,
                avertissements = listOf("legacy-utils.jar : jar illisible"),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertNotNull(modele.panneau.noteImpact)
        assertEquals(NiveauNoteImpact.AVERTISSEMENT, modele.panneau.noteImpact!!.niveau)
    }

    // ============================================================
    // État ERREUR
    // ============================================================

    @Test
    fun `etat ERREUR — pastille erreur visible`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.ERREUR,
                faits = 0,
                total = 191,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = listOf("Segment n°3 corrompu"),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertEquals(EtatPastilleIndexation.ERREUR, modele.pastille.etat)
        assertFalse(modele.pastille.masqueeEtroit)
    }

    @Test
    fun `etat ERREUR — action reindexer proposee`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.ERREUR,
                faits = 0,
                total = 191,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = listOf("Segment n°3 corrompu"),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertEquals(listOf(ActionDetailIndexation.REINDEXER), modele.panneau.actions)
    }

    @Test
    fun `etat ERREUR — note d impact erreur`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.ERREUR,
                faits = 0,
                total = 191,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = listOf("Segment n°3 corrompu"),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertNotNull(modele.panneau.noteImpact)
        assertEquals(NiveauNoteImpact.ERREUR, modele.panneau.noteImpact!!.niveau)
    }

    // ============================================================
    // Vue par moteur
    // ============================================================

    @Test
    fun `vue par moteur — Java present dans tous les etats sauf INITIAL`() {
        val etats =
            listOf(
                PhaseIndexation.CONSTRUCTION,
                PhaseIndexation.PRET,
                PhaseIndexation.PARTIEL,
                PhaseIndexation.ERREUR,
            )
        for (phase in etats) {
            val etat =
                EtatIndexation(
                    phase = phase,
                    faits = 0,
                    total = 0,
                    entreeCourante = null,
                    avertissements = emptyList(),
                    erreurs = emptyList(),
                )
            val modele = PresentationIndexation.afficher(etat)
            assertNotNull("moteur Java doit etre present en phase $phase", modele.panneau.moteurs)
            assertEquals(1, modele.panneau.moteurs!!.size)
            assertEquals(R.string.editor_indexation_moteur_java, modele.panneau.moteurs!![0].nom)
        }
    }

    @Test
    fun `vue par moteur — Java absent en INITIAL`() {
        val modele = PresentationIndexation.afficher(EtatIndexation.INITIAL)
        assertNull(modele.panneau.moteurs)
    }

    // ============================================================
    // Cas dégénérés
    // ============================================================

    @Test
    fun `pourcentage borne a 0-100 — faits superieur a total`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 250,
                total = 191,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertEquals(100, modele.pastille.pourcentage)
    }

    @Test
    fun `pourcentage nul si total inconnu`() {
        val etat =
            EtatIndexation(
                phase = PhaseIndexation.CONSTRUCTION,
                faits = 5,
                total = 0,
                entreeCourante = null,
                avertissements = emptyList(),
                erreurs = emptyList(),
            )
        val modele = PresentationIndexation.afficher(etat)
        assertNull(modele.pastille.pourcentage)
    }
}
