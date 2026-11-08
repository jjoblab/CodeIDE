package jo.codeide.tooling.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'instrumentation du daemon (Phase 1, ADR 0152) : la sonde T3
 * du fil lecteur (accumulation, drapeau d'activation, résumé consommé) et
 * la construction des drapeaux de ligne de commande du serveur
 * (`--instru-transport`, banc « socket seul »).
 */
class SondesInstruTest {
    @Test
    fun `la sonde coupee n enregistre rien`() {
        val sonde = SondeLecteurTransport()
        sonde.activer(false)

        sonde.frame(1_000L, 2_000L, 3_000L)
        sonde.frame(4_000L, 5_000L, 6_000L)

        assertNull("coupée, la sonde ne rend aucun résumé", sonde.prendreResume())
    }

    @Test
    fun `le resume compte les frames et se CONSOMME`() {
        val sonde = SondeLecteurTransport()
        sonde.activer(true)

        sonde.frame(1_000L, 2_000L, 3_000L)
        sonde.frame(3_000_000L, 4_000_000L, 5_000_000L)

        val resume = sonde.prendreResume()
        assertTrue(
            "le résumé porte le préfixe instru : $resume",
            resume != null && resume.startsWith("instru: lecteur —"),
        )
        assertTrue("le résumé compte 2 frames : $resume", resume!!.contains("2 frame(s)"))
        assertTrue("le résumé détaille la lecture : $resume", resume.contains("lecture min 1 / moy 1500 / max 3000 µs"))
        assertTrue(
            "le résumé détaille le décodage : $resume",
            resume.contains("décodage min 2 / moy 2001 / max 4000 µs"),
        )
        assertTrue("le résumé détaille le routage : $resume", resume.contains("routage min 3 / moy 2501 / max 5000 µs"))

        // Le résumé est CONSOMMÉ : sans nouvelle frame, plus rien.
        assertNull(sonde.prendreResume())
    }

    @Test
    fun `la sonde se coupe en cours de route - les frames suivantes sont ignorees`() {
        val sonde = SondeLecteurTransport()
        sonde.activer(true)
        sonde.frame(1_000L, 1_000L, 1_000L)
        sonde.activer(false)
        sonde.frame(9_000_000L, 9_000_000L, 9_000_000L)

        val resume = sonde.prendreResume()
        assertTrue("une seule frame comptée : $resume", resume!!.contains("1 frame(s)"))
    }

    @Test
    fun `parametres inactifs - aucune ligne de commande d instrumentation`() {
        assertEquals(emptyList<String>(), argumentsInstru(ParametresInstru.INACTIFS))
    }

    @Test
    fun `instrumentation seule - le drapeau instru-transport est passe`() {
        val parametres = ParametresInstru.INACTIFS.copy(instrumentationTransport = true)
        assertEquals(listOf("--instru-transport"), argumentsInstru(parametres))
    }

    @Test
    fun `banc seul - les cinq drapeaux du banc sont passes dans l ordre`() {
        val parametres =
            ParametresInstru.INACTIFS.copy(
                bancSocketSeul = true,
                bancLignes = 10_000,
                bancCadenceMs = 50,
                bancLongueurLigne = 200,
                bancContenu = "ansi",
            )
        assertEquals(
            listOf(
                "--banc-socket-seul",
                "--banc-lignes", "10000",
                "--banc-cadence-ms", "50",
                "--banc-longueur-ligne", "200",
                "--banc-contenu", "ansi",
            ),
            argumentsInstru(parametres),
        )
    }

    @Test
    fun `instrumentation ET banc - tous les drapeaux, instrumentation d abord`() {
        val parametres =
            ParametresInstru.INACTIFS.copy(
                instrumentationTransport = true,
                bancSocketSeul = true,
            )
        assertEquals(
            listOf(
                "--instru-transport",
                "--banc-socket-seul",
                "--banc-lignes", "60",
                "--banc-cadence-ms", "0",
                "--banc-longueur-ligne", "80",
                "--banc-contenu", "plain",
            ),
            argumentsInstru(parametres),
        )
    }
}
