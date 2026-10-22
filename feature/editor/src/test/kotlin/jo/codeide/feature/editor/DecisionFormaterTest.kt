package jo.codeide.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décisions de l'action « Formater le document » (ADR 0136 — P1-c :
 * la logique du choix de message et de visibilité est PURE, testée
 * JVM sans cadre Android ; le scénario d'appareil vit dans
 * `docs/TESTS_MANUELS.md`).
 */
public class DecisionFormaterTest {
    // ── La visibilité (phase 1 : onglet ouvert + session vivante). ────

    @Test
    public fun `visible - un onglet ouvert avec session vivante`() {
        assertTrue(DecisionFormater.visible(ongletOuvert = true, sessionVivante = true))
    }

    @Test
    public fun `invisible - sans onglet ouvert rien ne se lance`() {
        // Prompt P1-b : « Ne lance rien si aucun onglet n'est ouvert » —
        // l'action n'est même pas proposée.
        assertFalse(DecisionFormater.visible(ongletOuvert = false, sessionVivante = true))
    }

    @Test
    public fun `invisible - onglet ouvert sans serveur de langue`() {
        // Un .txt (ou un .java quand le moteur Java est absent) : la vue
        // n'a AUCUN formateur à invoquer — l'action n'est pas proposée
        // (phase 1 ; canFormat() prendra le relais en phase 2).
        assertFalse(DecisionFormater.visible(ongletOuvert = true, sessionVivante = false))
    }

    // ── Le message de retour (TRUE se tait, FALSE confirme, NULL dit). ─

    @Test
    public fun `message - texte modifie - aucun message`() {
        // Le formatage a MODIFIÉ le texte : l'utilisateur le VOIT, un
        // message serait du bruit (prompt P1-b).
        assertNull(DecisionFormater.messagePour(resultat = true))
    }

    @Test
    public fun `message - deja conforme`() {
        assertEquals(R.string.editor_formater_deja, DecisionFormater.messagePour(resultat = false))
    }

    @Test
    public fun `message - aucun formateur`() {
        // Kotlin (serveur sans mise en forme) ou un fichier sans serveur :
        // l'indisponibilité s'EXPLIQUE (message complet du prompt).
        assertEquals(R.string.editor_formater_indisponible, DecisionFormater.messagePour(resultat = null))
    }

    // ── P1-a : le journal honnête — la résolution du langage. ──────────

    @Test
    public fun `langage - extensions des trois langues`() {
        assertEquals("Kotlin", langageLspDuFichier("src/main/Exemple.kt"))
        assertEquals("Kotlin", langageLspDuFichier("build.gradle.kts"))
        assertEquals("XML", langageLspDuFichier("app/src/main/res/layout/activite.xml"))
        assertEquals("Java", langageLspDuFichier("src/com/exemple/Main.java"))
    }

    @Test
    public fun `langage - insensible a la casse`() {
        assertEquals("Kotlin", langageLspDuFichier("notes/KT.KT"))
        assertEquals("XML", langageLspDuFichier("notes/NOTE.XML"))
    }

    @Test
    public fun `langage - sans extension de langue - jamais java par defaut`() {
        // L'ancien journal étiquetait Java TOUT fichier inconnu (else ->
        // "Java") — un README branché par langage reconnu se disait Java :
        // honnête désormais, plus AUCUN langage.
        assertNull(langageLspDuFichier("notes/lisez-moi.txt"))
        assertNull(langageLspDuFichier("Makefile"))
        assertNull(langageLspDuFichier("build.gradle"))
    }

    @Test
    public fun `langage - les predicats d extension nourrissent les onglets`() {
        // Les replis estJava/estKotlin/estXml partagent les MÊMES
        // prédicats : langage reconnu OU extension, une seule écriture.
        val ongletKotlin = EditorTabState(uri = "u", cheminRelatif = "c", nom = "Main.kt", langage = null)
        val ongletReconnu = EditorTabState(uri = "u", cheminRelatif = "c", nom = "LISEZMOI", langage = "kotlin")
        val ongletAucun = EditorTabState(uri = "u", cheminRelatif = "c", nom = "notes.txt", langage = null)
        assertTrue(ongletKotlin.estKotlin())
        assertTrue(ongletReconnu.estKotlin())
        assertTrue(extensionKotlin("Script.KTS"))
        assertFalse(ongletAucun.estJava())
        assertFalse(ongletAucun.estKotlin())
        assertFalse(ongletAucun.estXml())
    }
}
