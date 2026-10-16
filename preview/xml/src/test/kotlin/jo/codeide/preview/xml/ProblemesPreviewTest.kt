package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Règles de problèmes de l'aperçu (PE6c, maquette
 * `docs/mockups/apercu-xml.html` § « Données enrichies ») — fonctions
 * pures, testées comme [SelectionPreviewTest] : seuils, conversion
 * densité, sévérités, évaluation d'ensemble.
 */
class ProblemesPreviewTest {
    private fun vue(
        largeurPx: Int,
        hauteurPx: Int,
        cliquable: Boolean = false,
        classe: String = "Button",
    ): InfoVueRendu =
        InfoVueRendu(
            gauche = 0,
            haut = 0,
            droite = largeurPx,
            bas = hauteurPx,
            libelle = classe,
            debutElement = 0,
            finElement = 10,
            indiceParent = -1,
            profondeur = 0,
            classe = classe,
            cliquable = cliquable,
        )

    // ── Règle « classe absente » (cadre de remplacement G15) ──

    @Test
    fun `classe absente - MockView detecte, vraie classe ignoree`() {
        assertTrue(ProblemesPreview.classeAbsente(vue(120, 48, classe = InfoVueRendu.CLASSE_REMPLACEMENT)))
        assertFalse(ProblemesPreview.classeAbsente(vue(120, 48, classe = "CarteProfil")))
        assertFalse(ProblemesPreview.classeAbsente(vue(120, 48, classe = "")))
    }

    // ── Règle « cible tactile » (cliquable < 47,5 dp) ──

    @Test
    fun `cible tactile - cliquable trop petit signale`() {
        // 88 px à 320 dpi = 44 dp < 47,5 — sous le minimum 48×48.
        val fautive = vue(largeurPx = 88, hauteurPx = 88, cliquable = true)
        assertTrue(ProblemesPreview.cibleTactile(fautive, densiteDpi = 320))
    }

    @Test
    fun `cible tactile - cliquable au minimum passe`() {
        // 96 px à 320 dpi = 48 dp exactement — le seuil 47,5 tolère.
        val correcte = vue(largeurPx = 96, hauteurPx = 96, cliquable = true)
        assertFalse(ProblemesPreview.cibleTactile(correcte, densiteDpi = 320))
    }

    @Test
    fun `cible tactile - un seul petit cote suffit`() {
        // Large mais plat : 300 × 76 px à 440 dpi = 109 × 27,6 dp — le
        // PLUS PETIT côté décide (miroir `Math.min(q.w, q.h)` maquette).
        val plate = vue(largeurPx = 300, hauteurPx = 76, cliquable = true)
        assertTrue(ProblemesPreview.cibleTactile(plate, densiteDpi = 440))
    }

    @Test
    fun `cible tactile - non cliquable jamais signalee`() {
        val decoratif = vue(largeurPx = 10, hauteurPx = 10, cliquable = false)
        assertFalse(ProblemesPreview.cibleTactile(decoratif, densiteDpi = 440))
    }

    @Test
    fun `cible tactile - densite inconnue se tait (jamais de faux positif)`() {
        val petite = vue(largeurPx = 10, hauteurPx = 10, cliquable = true)
        assertFalse(ProblemesPreview.cibleTactile(petite, densiteDpi = 0))
        assertFalse(ProblemesPreview.cibleTactile(petite, densiteDpi = -440))
    }

    @Test
    fun `cible tactile - conversion par la densite du PROFIL`() {
        // 40 dp visés : à 160 dpi = 40 px (insuffisant), à 80 dpi = 40 px
        // = 80 dp (largement passé) — même px, verdict différent.
        val vue40px = vue(largeurPx = 40, hauteurPx = 40, cliquable = true)
        assertTrue(ProblemesPreview.cibleTactile(vue40px, densiteDpi = 160))
        assertFalse(ProblemesPreview.cibleTactile(vue40px, densiteDpi = 80))
    }

    // ── Évaluation d'ensemble ──

    @Test
    fun `evaluer - arbre vide - aucun probleme`() {
        assertTrue(ProblemesPreview.evaluer(emptyList(), densiteDpi = 440).isEmpty())
    }

    @Test
    fun `evaluer - indices, severites et multi-problemes`() {
        val vues =
            listOf(
                // 0 : racine saine (conteneur, non cliquable).
                vue(largeurPx = 400, hauteurPx = 800, classe = "FrameLayout"),
                // 1 : classe introuvable (ERREUR) ET cible trop petite
                // (AVERTISSEMENT) — la pastille de l'UI compte 2.
                vue(88, 88, cliquable = true, classe = InfoVueRendu.CLASSE_REMPLACEMENT),
                // 2 : saine.
                vue(200, 200, cliquable = true),
            )
        val problemes = ProblemesPreview.evaluer(vues, densiteDpi = 320)
        // Vue 1 : CLASSE_ABSENTE + CIBLE_TACTILE (44 dp) ; vue 2 passe (100 dp).
        assertEquals(2, problemes.size)
        // La vue 1 porte les DEUX problèmes, ERREUR d'abord (ordre des règles).
        assertEquals(1, problemes[0].indiceVue)
        assertEquals(ProblemesPreview.Severite.ERREUR, problemes[0].severite)
        assertEquals(ProblemesPreview.GenreProbleme.CLASSE_ABSENTE, problemes[0].genre)
        assertEquals(1, problemes[1].indiceVue)
        assertEquals(ProblemesPreview.Severite.AVERTISSEMENT, problemes[1].severite)
        assertEquals(ProblemesPreview.GenreProbleme.CIBLE_TACTILE, problemes[1].genre)
        // La vue 2 (cliquable 200×200 px = 100×100 dp) passe.
        assertEquals(listOf(1, 1), problemes.map { it.indiceVue })
    }

    @Test
    fun `evaluer - densite inconnue - seule la classe absente parle`() {
        val vues =
            listOf(
                vue(10, 10, cliquable = true),
                vue(10, 10, cliquable = true, classe = InfoVueRendu.CLASSE_REMPLACEMENT),
            )
        val problemes = ProblemesPreview.evaluer(vues, densiteDpi = 0)
        assertEquals(1, problemes.size)
        assertEquals(1, problemes[0].indiceVue)
        assertEquals(ProblemesPreview.GenreProbleme.CLASSE_ABSENTE, problemes[0].genre)
    }
}
