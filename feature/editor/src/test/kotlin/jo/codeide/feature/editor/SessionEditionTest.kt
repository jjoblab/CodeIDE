package jo.codeide.feature.editor

import jo.codeeditor.document.EditorDocument
import jo.codeeditor.session.EditorSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Intégration réelle avec les classes pures de cel-core (critère
 * d'acceptation de l'étape 15, prompt compagnon section 7) : construire de
 * **vraies** `EditorDocument`/`EditorSession` — pas des fakes — sans dépendre
 * de `EditorView`, pour vérifier que le contrat utilisé par l'espace de
 * travail tient : création, langue, lecture du texte courant, notification
 * d'édition (le déclencheur du point de modification et de l'auto-sauvegarde)
 * et libération.
 */
class SessionEditionTest {
    @Test
    fun `le document reel fait l aller retour du texte`() {
        val document = EditorDocument.of("fun main() {\n}\n")

        assertEquals("fun main() {\n}\n", document.getText())
        // Le saut de ligne final compte pour une ligne vide terminale.
        assertEquals(3, document.lineCount())
    }

    @Test
    fun `la session reelle relit le texte et la langue demandee`() {
        val session = EditorSession(EditorDocument.of("val x = 1"))

        session.setLanguage("kotlin")

        assertEquals("kotlin", session.getLanguage())
        assertEquals("val x = 1", session.getText())
    }

    @Test
    fun `une langue inconnue reste un repli neutre sans erreur`() {
        val session = EditorSession(EditorDocument.of("contenu"))

        // Le repli neutre de la section 2.4 : aucune coloration, jamais
        // un blocage — setLanguage tolère un nom non enregistré.
        session.setLanguage("langage-qui-n-existe-pas")

        assertEquals("langage-qui-n-existe-pas", session.getLanguage())
        assertEquals("contenu", session.getText())
    }

    @Test
    fun `replaceRange modifie le texte et notifie l auditeur d edition`() {
        var notifications = 0
        val session = EditorSession(EditorDocument.of("ab"))
        session.addOnTextEditListener { _, _, _ -> notifications++ }

        session.replaceRange(0, 0, "x")

        assertEquals("xab", session.getText())
        assertTrue("l'auditeur d'édition a été notifié", notifications > 0)
    }

    @Test
    fun `dispose libere la session sans casse ulterieure`() {
        val session = EditorSession(EditorDocument.of("texte"))
        session.setLanguage("kotlin")

        // La libération impérative (fuite du thread de restyle sinon,
        // prompt compagnon 2.4) : appelable puis silencieuse.
        session.dispose()
        session.dispose()
    }
}
