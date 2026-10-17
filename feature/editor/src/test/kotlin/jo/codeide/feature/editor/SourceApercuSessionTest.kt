package jo.codeide.feature.editor

import jo.codeeditor.document.EditorDocument
import jo.codeeditor.document.Selection
import jo.codeeditor.session.EditorSession
import jo.codeide.core.domain.RegistreSourcesApercu
import jo.codeide.core.domain.SourceApercu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adaptateur session d'édition → port [SourceApercu] (PE6-0, maquette
 * « Partage de la session ») : intégration réelle avec les classes
 * pures de cel-core (miroir [SessionEditionTest]) — le port ne voit
 * aucun type cel, chaque opération délègue à la VRAIE session.
 */
class SourceApercuSessionTest {
    private fun adaptateur(texte: String = "<LinearLayout></LinearLayout>"): SourceApercuSession =
        SourceApercuSession(EditorSession(EditorDocument.of(texte)), "uri://a", "res/layout/a.xml")

    @Test
    fun `texte - l etat vivant de la session`() {
        val source = adaptateur("val x = 1")
        assertEquals("val x = 1", source.texte())
    }

    @Test
    fun `remplacer - edition atomique, une seule entree d historique`() {
        val source = adaptateur("<TextView/>")
        assertTrue(source.remplacer(0, "<TextView/>".length, "<Button/>"))
        assertEquals("<Button/>", source.texte())
        // L'annulation est celle de l'éditeur (pas un historique parallèle).
        assertTrue(source.annuler())
        assertEquals("<TextView/>", source.texte())
        assertTrue(source.retablir())
        assertEquals("<Button/>", source.texte())
    }

    @Test
    fun `remplacer - bornes hors document - false sans exception`() {
        val source = adaptateur("abc")
        assertFalse(source.remplacer(-5, 2, "x"))
        assertFalse(source.remplacer(2, 99, "x"))
        assertEquals("abc", source.texte())
    }

    @Test
    fun `peut annuler peut retablir - l etat de la pile suit les editions (PE10c)`() {
        val source = adaptateur("<TextView/>")
        // Historique vide : les deux icônes sont DÉSACTIVÉES.
        assertFalse(source.peutAnnuler())
        assertFalse(source.peutRetablir())
        assertTrue(source.remplacer(0, 10, "<Button/>"))
        // Une édition : annuler s'allume, rétablir reste éteint.
        assertTrue(source.peutAnnuler())
        assertFalse(source.peutRetablir())
        assertTrue(source.annuler())
        // Annulation : rétablir s'allume, annuler reste allumé (l'étape
        // d'origine reste consommable — miroir updateEditBar).
        assertTrue(source.peutRetablir())
        assertTrue(source.retablir())
        // Rétablissement : la pile redo est épuisée de nouveau.
        assertFalse(source.peutRetablir())
        assertTrue(source.peutAnnuler())
    }

    @Test
    fun `selection - aller-retour`() {
        val source = adaptateur("<TextView/>")
        source.definirSelection(1, 9)
        assertEquals(1 to 9, source.selection())
    }

    @Test
    fun `selection - compatible avec Selection de la bibliotheque`() {
        val source = adaptateur("<TextView/>")
        source.definirSelection(Selection.range(0, 9).start, Selection.range(0, 9).end)
        assertEquals(0, source.selection().first)
        assertEquals(9, source.selection().second)
    }

    @Test
    fun `ecouteur - mutation notifiee puis retiree`() {
        val session = EditorSession(EditorDocument.of("abc"))
        val source = SourceApercuSession(session, "uri://a", "a.xml")
        var mutations = 0
        val ecouteur = SourceApercu.EcouteurMutation { mutations += 1 }
        source.ajouterEcouteur(ecouteur)
        session.replaceRange(0, 0, "x")
        assertEquals(1, mutations)
        // Le retrait tombe sur le MÊME pont (mis en cache) — sinon
        // l'écouteur resterait branché et l'aperçu se re-soumettrait en
        // boucle après la déliaison.
        source.retirerEcouteur(ecouteur)
        session.replaceRange(0, 0, "y")
        assertEquals(1, mutations)
    }

    @Test
    fun `migrer - uri et chemin suivent le deplacement du fichier`() {
        val source = adaptateur()
        source.migrer("uri://b", "res/layout/b.xml")
        assertEquals("uri://b", source.uri)
        assertEquals("res/layout/b.xml", source.cheminRelatif)
    }

    @Test
    fun `registre - l adaptateur s y publie et y migre (integration SessionSuivie)`() {
        val registre = RegistreSourcesApercu()
        val session = SessionSuivie(EditorSession(EditorDocument.of("x")), "uri://a", "a.xml", registre)
        assertEquals(session.sourceApercu, registre.sourceDe("uri://a"))
        session.migrer("uri://b", "b.xml")
        assertNull(registre.sourceDe("uri://a"))
        assertEquals(session.sourceApercu, registre.sourceDe("uri://b"))
        session.disposer()
        assertNull(registre.sourceDe("uri://b"))
        assertTrue(session.liberee)
    }

    @Test
    fun `uriProjet - porte l uri document du projet (suivi de l espace)`() {
        val source = adaptateur()
        assertNull(source.uriProjet)
        source.uriProjet = "uri://projet"
        assertEquals("uri://projet", source.uriProjet)
    }
}
