package jo.codeide.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décisions des actions de mise en forme (ADR 0136 — P1-c puis
 * phase 2 : la logique du choix de message et de visibilité est PURE,
 * testée JVM sans cadre Android ; les scénarios d'appareil vivent dans
 * `docs/TESTS_MANUELS.md`).
 */
public class DecisionFormaterTest {
    // ── La visibilité (phase 2 : onglet ouvert + canFormat()). ──────

    @Test
    public fun `visible - un onglet ouvert avec formateur disponible`() {
        // Phase 2 (ADR 0136 D4) : `canFormat()` — le langage courant
        // expose un formateur (le serveur annonce la mise en forme et
        // la capacité n'est pas éteinte dans les réglages de langage).
        assertTrue(DecisionFormater.visible(ongletOuvert = true, formateurDisponible = true))
    }

    @Test
    public fun `invisible - sans onglet ouvert rien ne se lance`() {
        // Prompt P1-b : « Ne lance rien si aucun onglet n'est ouvert » —
        // l'action n'est même pas proposée.
        assertFalse(DecisionFormater.visible(ongletOuvert = false, formateurDisponible = true))
    }

    @Test
    public fun `invisible - onglet ouvert sans formateur`() {
        // Un .txt (coloration seule — la déconnexion restaure
        // EmptyLanguage) ou une capacité « Formatage » coupée dans
        // l'écran Intelligence du code : `canFormat()` faux, l'action
        // n'est pas proposée — la vue répond, la session n'est plus un
        // proxy (phase 2 ; le serveur Kotlin, muet sur la mise en forme
        // en 1.2.0, l'annonce depuis codeide-lsp 1.3.0).
        assertFalse(DecisionFormater.visible(ongletOuvert = true, formateurDisponible = false))
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

    // ── Phase 2 : les MÊMES messages portent la sélection (D4). ─────

    @Test
    public fun `message - la selection partage les retours du document`() {
        // `formatSelection` : `null` = aucun formateur ; `false` = refus
        // propre (formateur sans plages, sélection déjà conforme) — le
        // prompt §3.2.5 impose les MÊMES messages que le document ;
        // `true` (texte modifié) reste silencieux : l'utilisateur le voit.
        assertEquals(R.string.editor_formater_indisponible, DecisionFormater.messagePour(resultat = null))
        assertEquals(R.string.editor_formater_deja, DecisionFormater.messagePour(resultat = false))
        assertNull(DecisionFormater.messagePour(resultat = true))
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
