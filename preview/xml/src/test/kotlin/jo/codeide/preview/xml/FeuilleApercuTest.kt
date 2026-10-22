package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Modèles d'alimentation de la feuille inférieure (PE6d, maquette §
 * « Feuille inférieure : arbre, attributs, problèmes, source ») :
 * arbre (miroir `renderTree`), attributs LECTURE SEULE (miroir
 * `renderAttrs` sans l'éditeur — PE7d), problèmes (miroir `renderProbs`)
 * et plage de source (miroir `renderSrc` + `ownerOf`).
 */
public class FeuilleApercuTest {
    /** Arbre simple : racine + TextView (2 problèmes) + LinearLayout vide. */
    private fun vues(): List<InfoVueRendu> =
        listOf(
            InfoVueRendu(
                gauche = 0,
                haut = 0,
                droite = 400,
                bas = 800,
                libelle = "ConstraintLayout @+id/root",
                debutElement = 0,
                finElement = 30,
                indiceParent = -1,
                profondeur = 0,
                classe = "ConstraintLayout",
                idRes = "root",
                conteneur = true,
            ),
            InfoVueRendu(
                gauche = 24,
                haut = 16,
                droite = 376,
                bas = 64,
                libelle = "Button @+id/action",
                debutElement = 30,
                finElement = 90,
                indiceParent = 0,
                profondeur = 1,
                classe = "Button",
                idRes = "action",
                cliquable = true,
            ),
            InfoVueRendu(
                gauche = 24,
                haut = 80,
                droite = 376,
                bas = 400,
                libelle = "LinearLayout @+id/panier",
                debutElement = 90,
                finElement = 120,
                indiceParent = 0,
                profondeur = 1,
                classe = "LinearLayout",
                idRes = "panier",
                conteneur = true,
                orientation = InfoVueRendu.ORIENTATION_VERTICALE,
            ),
        )

    private val problemes =
        listOf(
            ProblemesPreview.ProblemeApercu(
                1,
                ProblemesPreview.Severite.ERREUR,
                ProblemesPreview.GenreProbleme.CLASSE_ABSENTE,
            ),
            ProblemesPreview.ProblemeApercu(
                1,
                ProblemesPreview.Severite.AVERTISSEMENT,
                ProblemesPreview.GenreProbleme.CIBLE_TACTILE,
            ),
        )

    /** La vue avec un span refait (InfoVueRendu n'est pas une data class — pas de copy). */
    private fun avecSpan(
        vue: InfoVueRendu,
        debut: Int,
        fin: Int,
    ): InfoVueRendu =
        InfoVueRendu(
            gauche = vue.gauche,
            haut = vue.haut,
            droite = vue.droite,
            bas = vue.bas,
            libelle = vue.libelle,
            debutElement = debut,
            finElement = fin,
            indiceParent = vue.indiceParent,
            profondeur = vue.profondeur,
            classe = vue.classe,
            idRes = vue.idRes,
            conteneur = vue.conteneur,
        )

    @Test
    fun `l'arbre suit le pré-ordre avec indentation par profondeur et badge de problèmes`() {
        val entrees = FeuilleApercu.entreesArbre(vues(), problemes, densiteDpi = 160)
        assertEquals(3, entrees.size)
        // Racine : profondeur 0 → indentation 8 ; sans problème → dimensions dp.
        assertEquals(0, entrees[0].indice)
        assertEquals(8, entrees[0].indentation)
        assertEquals("400×800", entrees[0].badge)
        assertTrue(!entrees[0].aProblemes)
        assertEquals("C", entrees[0].monogramme)
        assertEquals("root", entrees[0].idNom)
        // Vue avec DEUX problèmes : badge « ! 2 » (miroir renderTree).
        assertEquals(8 + 16, entrees[1].indentation)
        assertEquals("! 2", entrees[1].badge)
        assertTrue(entrees[1].aProblemes)
    }

    @Test
    fun `l'arbre sans densité — badge en px, sans exception`() {
        val entrees = FeuilleApercu.entreesArbre(vues(), emptyList(), densiteDpi = 0)
        assertEquals("400×800", entrees[0].badge)
    }

    @Test
    fun `l'arbre vide — aucune rangée`() {
        assertTrue(FeuilleApercu.entreesArbre(emptyList(), emptyList(), 160).isEmpty())
    }

    @Test
    fun `les groupes d'attributs — identité, disposition et mesures en dp`() {
        val groupes = FeuilleApercu.groupesAttributs(vues()[1], densiteDpi = 160)
        assertEquals(3, groupes.size)
        val identite = groupes[0]
        assertEquals("Identité", identite.titre)
        assertTrue(identite.entrees.any { it.libelle == "classe" && it.valeur == "Button" })
        assertTrue(identite.entrees.any { it.libelle == "id" && it.valeur == "@+id/action" })
        val disposition = groupes[1]
        assertEquals("Disposition", disposition.titre)
        // 352 px @160dpi → 176 dp ; marges nulles masquées (miroir maquette).
        assertTrue(disposition.entrees.any { it.libelle == "layout_width" && it.valeur == "352dp" })
        assertTrue(disposition.entrees.none { it.libelle.startsWith("layout_margin") })
        assertTrue(disposition.entrees.none { it.libelle.startsWith("padding") })
        val mesure = groupes[2]
        assertEquals("Mesuré", mesure.titre)
        assertTrue(mesure.entrees.any { it.libelle == "cliquable" && it.valeur == "oui" })
        // Orientation déclarée (LinearLayout vertical).
        val racine = FeuilleApercu.groupesAttributs(vues()[2], 160)[1]
        assertTrue(racine.entrees.any { it.libelle == "orientation" && it.valeur == "vertical" })
    }

    @Test
    fun `les problèmes affichables — sévérité et genre portés à l'UI`() {
        val entrees = FeuilleApercu.entreesProblemes(problemes)
        assertEquals(2, entrees.size)
        assertTrue(entrees[0].erreur)
        assertTrue(!entrees[1].erreur)
        assertEquals(ProblemesPreview.GenreProbleme.CLASSE_ABSENTE, entrees[0].genre)
        assertEquals(1, entrees[0].indiceVue)
    }

    @Test
    fun `la plage de source suit le span de la vue (lignes 1-based)`() {
        val texte = "<LinearLayout>\n    <Button android:id=\"@+id/action\" />\n    <LinearLayout />\n</LinearLayout>"
        val button = avecSpan(vues()[1], texte.indexOf("<Button"), texte.indexOf("/>") + 2)
        val plage = FeuilleApercu.plageSource(texte, button)
        assertEquals(texte.lines().indexOfFirst { it.contains("<Button") } + 1, plage?.first)
        assertEquals(texte.lines().indexOfFirst { it.contains("/>") } + 1, plage?.last)
    }

    @Test
    fun `la plage de source refuse sans vue ni span (sélection racine)`() {
        val texte = "<LinearLayout></LinearLayout>"
        assertNull(FeuilleApercu.plageSource(texte, null))
        assertNull(FeuilleApercu.plageSource(texte, avecSpan(vues()[0], -1, -1)))
        assertNull(FeuilleApercu.plageSource("", vues()[1]))
    }

    @Test
    fun `la vue d'une ligne — le plus petit span contenant (miroir ownerOf)`() {
        val texte = "<LinearLayout>\n    <Button android:id=\"@+id/action\" />\n</LinearLayout>"
        val racine = avecSpan(vues()[0], 0, texte.length)
        val button = avecSpan(vues()[1], texte.indexOf("<Button"), texte.indexOf("/>") + 2)
        val vuesBornees = listOf(racine, button)
        // Ligne 2 (Button) : la vue la plus INTERNE gagne.
        assertEquals(1, FeuilleApercu.vueDeLigne(vuesBornees, texte, 2))
        // Ligne 3 (fermante du conteneur) : la racine seule contient.
        assertEquals(0, FeuilleApercu.vueDeLigne(vuesBornees, texte, 3))
        // Hors document : aucune.
        assertEquals(-1, FeuilleApercu.vueDeLigne(vuesBornees, texte, 9))
        assertEquals(-1, FeuilleApercu.vueDeLigne(emptyList(), texte, 1))
    }

    // ── PE14-S1 (ADR 0139) — la section « Système » de l'arbre ───────────

    /** Les BARRES d'un décor : status bar 24 dp + barre de navigation 48 dp (440 dpi). */
    private fun barresSysteme(): List<InfoVueSysteme> =
        listOf(
            InfoVueSysteme(
                gauche = 0,
                haut = 0,
                droite = 440,
                bas = 66,
                classe = "StatusBar",
                type = InfoVueSysteme.TYPE_STATUS_BAR,
            ),
            InfoVueSysteme(
                gauche = 0,
                haut = 814,
                droite = 440,
                bas = 946,
                classe = "NavigationBar",
                type = InfoVueSysteme.TYPE_NAVIGATION_BAR,
            ),
        )

    @Test
    fun `entreesSysteme - une entete puis une rangee par barre, jamais selectionnables`() {
        val entrees =
            FeuilleApercu.entreesSysteme(
                vuesSysteme = barresSysteme(),
                densiteDpi = 440,
                entete = "Système",
                libelle = { vue -> if (vue.type == InfoVueSysteme.TYPE_STATUS_BAR) "Barre de statut" else vue.classe },
            )
        assertEquals("entête + deux barres", 3, entrees.size)
        assertEquals(FeuilleApercu.GenreRangee.ENTETE_SYSTEME, entrees[0].genre)
        assertEquals("l'entête porte le libellé, rien d'autre", "Système", entrees[0].classe)
        assertTrue(entrees[0].monogramme.isEmpty())
        assertTrue(entrees[0].badge.isEmpty())
        val status = entrees[1]
        assertEquals(FeuilleApercu.GenreRangee.VUE_SYSTEME, status.genre)
        assertEquals("monogramme de la CLASSE (pas du libellé)", "S", status.monogramme)
        assertEquals("Barre de statut", status.classe)
        assertEquals("440 px × 160/440 = 160 dp ; 66 px = 24 dp", "160×24 dp", status.badge)
        assertEquals("indices NÉGATIFS distincts (clé DiffUtil)", -2, status.indice)
        assertEquals(-3, entrees[2].indice)
        assertEquals("la barre de navigation garde sa classe en repli", "NavigationBar", entrees[2].classe)
    }

    @Test
    fun `entreesSysteme - sans decor la section n existe pas`() {
        assertTrue(
            FeuilleApercu.entreesSysteme(emptyList(), densiteDpi = 440, entete = "Système", libelle = { "" }).isEmpty(),
        )
    }

    @Test
    fun `entreesSysteme - sans densite le badge tombe en px`() {
        val entrees =
            FeuilleApercu.entreesSysteme(
                vuesSysteme = barresSysteme().take(1),
                densiteDpi = 0,
                entete = "Système",
                libelle = { vue -> vue.classe },
            )
        assertEquals("440×66px", entrees[1].badge)
    }
}
