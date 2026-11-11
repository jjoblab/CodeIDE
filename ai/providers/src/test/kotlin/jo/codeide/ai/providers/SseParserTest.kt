package jo.codeide.ai.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Tests du parseur SSE maison (ADR 0123 option A) — les cas limites du
 * plan IA-1 : lignes `data:` multiples, événements inconnus, UTF-8 coupé
 * en milieu de séquence, fin de flux.
 */
class SseParserTest {
    private val parseur = SseParser()

    @Test
    fun `un flux nominal produit un événement par bloc`() {
        val evenements = parseur.parseLines(listOf("data: premier", "", "data: second", "").asSequence()).toList()

        assertEquals(2, evenements.size)
        assertEquals("premier", evenements[0].data)
        assertNull(evenements[0].event)
        assertEquals("second", evenements[1].data)
    }

    @Test
    fun `plusieurs lignes data d'un même événement sont jointes par un retour à la ligne`() {
        val evenements =
            parseur
                .parseLines(
                    listOf(
                        "data: première ligne",
                        "data: seconde ligne",
                        "",
                    ).asSequence(),
                ).toList()

        assertEquals(1, evenements.size)
        assertEquals("première ligne\nseconde ligne", evenements[0].data)
    }

    @Test
    fun `le nom d'événement est capturé et les commentaires ignorés`() {
        val evenements =
            parseur
                .parseLines(
                    listOf(
                        ": keep-alive",
                        "event: message_start",
                        "data: {}",
                        "",
                    ).asSequence(),
                ).toList()

        assertEquals(1, evenements.size)
        assertEquals("message_start", evenements[0].event)
        assertEquals("{}", evenements[0].data)
    }

    @Test
    fun `les champs id et retry et les champs inconnus sont ignorés`() {
        val evenements =
            parseur
                .parseLines(
                    listOf(
                        "id: evt_42",
                        "retry: 2500",
                        "inconnu: valeur",
                        "data: corps",
                        "",
                    ).asSequence(),
                ).toList()

        assertEquals(1, evenements.size)
        assertEquals("corps", evenements[0].data)
        assertNull(evenements[0].event)
    }

    @Test
    fun `la valeur data sans espace après les deux-points est conservée telle quelle`() {
        val evenements = parseur.parseLines(listOf("data:sans-espace", "").asSequence()).toList()

        assertEquals(1, evenements.size)
        assertEquals("sans-espace", evenements[0].data)
    }

    @Test
    fun `un événement en mémoire à la fin du flux est délivré`() {
        val evenements = parseur.parseLines(listOf("event: message_stop", "data: {}").asSequence()).toList()

        assertEquals(1, evenements.size)
        assertEquals("message_stop", evenements[0].event)
        assertEquals("{}", evenements[0].data)
    }

    @Test
    fun `un caractère multioctet coupé au milieu d'un segment est reconstitué`() {
        // é = 0xC3 0xA9 en UTF-8 : le flux ne rend qu'un octet par lecture,
        // forçant le décodeur à garder la séquence partielle en attente.
        val texte = "data: {\"texte\":\"éàü\"}\n\n"
        val goutteAGoutte =
            object : InputStream() {
                private var position = 0

                override fun read(): Int = if (position < texte.toByteArray().size) {
                    texte.toByteArray()[position++].toInt() and 0xFF
                } else {
                    -1
                }
            }

        val evenements = parseur.parseBytes(goutteAGoutte).toList()

        assertEquals(1, evenements.size)
        assertEquals("{\"texte\":\"éàü\"}", evenements[0].data)
    }

    @Test
    fun `un octet final tronqué produit un caractère de remplacement (JSON invalide en aval)`() {
        // Le flux s'arrête au milieu du « é » (0xC3 seul, 17ᵉ octet) :
        // le décodeur remplace — l'adaptateur verra un JSON invalide et
        // répondra MalformedResponse.
        val octets = "data: {\"texte\":\"\u00e9".toByteArray(Charsets.UTF_8).copyOfRange(0, 17)

        val evenements =
            parseur.parseBytes(ByteArrayInputStream(octets)).toList()

        assertEquals(1, evenements.size)
        assertEquals("\uFFFD", evenements[0].data.takeLast(1))
    }

    @Test
    fun `une donnée factice sert de garde-fou sur la ressource dorée`() {
        // Garde-fou : la ressource dorée nominale Anthropic doit contenir
        // l'événement terminal.
        val dore = ressource("anthropic-nominal.sse")
        val evenements = parseur.parseLines(dore.lineSequence()).toList()

        assertEquals("message_stop", evenements.last().event)
    }
}
