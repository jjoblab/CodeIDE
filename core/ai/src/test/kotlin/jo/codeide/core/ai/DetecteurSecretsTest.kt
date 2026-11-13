package jo.codeide.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du balayage préventif de secrets (mission IA, lot IA-4,
 * ADR 0124 §4) : motifs fermés, étiquette SANS valeur, un contexte
 * contenant une clé factice déclenche l'alerte (critère d'acceptation).
 */
class DetecteurSecretsTest {
    @Test
    fun `une clé factice sk- est détectée`() {
        val etiquettes = DetecteurSecrets.balayer("val cle = \"sk-factice1234567890abcdef\"")

        assertEquals(listOf("clé sk-"), etiquettes)
    }

    @Test
    fun `un jeton GitHub ghp_ factice est détecté`() {
        val etiquettes = DetecteurSecrets.balayer("token=ghp_fFKAa0bB1cC2dD3eE4fF5gG6hH7")

        assertEquals(listOf("jeton GitHub ghp_"), etiquettes)
    }

    @Test
    fun `une clé privée PEM est détectée`() {
        val etiquettes = DetecteurSecrets.balayer("-----BEGIN RSA PRIVATE KEY-----")

        assertEquals(listOf("clé privée"), etiquettes)
    }

    @Test
    fun `les en-têtes Authorization et Bearer sont détectés`() {
        val etiquettes =
            DetecteurSecrets.balayer(
                "Authorization: Bearer abcdef1234567890abcdef\n" +
                    "authorization: Basic dXNlcm5hbWU6bW90ZGVwYXNzZQ==",
            )

        assertTrue("jeton Bearer" in etiquettes)
        assertTrue("en-tête Authorization" in etiquettes)
    }

    @Test
    fun `du code ordinaire ne déclenche rien`() {
        val etiquettes =
            DetecteurSecrets.balayer(
                "class MainActivity : AppCompatActivity() {\n" +
                    "    override fun onCreate(savedInstanceState: Bundle?) {\n" +
                    "        super.onCreate(savedInstanceState)\n" +
                    "    }\n" +
                    "}\n",
            )

        assertTrue(etiquettes.isEmpty())
    }

    @Test
    fun `les alertes situent le motif et ne portent JAMAIS la valeur`() {
        val valeur = "sk-super secrete1234567890abcdef".replace(" ", "")
        val alertes = DetecteurSecrets.balayer("const = \"$valeur\"", "app/Config.kt")

        assertEquals(listOf(AlerteSecret("clé sk-", "app/Config.kt")), alertes)
        assertFalse(alertes.any { it.motif.contains(valeur) })
    }

    @Test
    fun `les motifs sont dédupliqués`() {
        val etiquettes =
            DetecteurSecrets.balayer(
                "a = sk-aaaa1111111111111111\nb = sk-bbbb2222222222222222",
            )

        assertEquals(listOf("clé sk-"), etiquettes)
    }
}
