package jo.codeide.core.domain.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de la garde de sécurité des chemins générés (étape 8 — section 11 :
 * rejeter `..`, chemins absolus, séparateurs invalides, doublons, noms
 * réservés ; ne jamais écraser un fichier existant).
 *
 * La garde s'applique **après** substitution : ces chemins simulent le pire
 * contenu qu'une variable hostile pourrait injecter.
 */
class TemplatePathGuardTest {
    @Test
    fun `un chemin relatif simple est sûr`() {
        assertNull(TemplatePathGuard.valider("src/com/exemple/Main.txt"))
    }

    @Test
    fun `un chemin unicode et pointillé est sûr`() {
        assertNull(TemplatePathGuard.valider("docs/README-éclair.txt"))
    }

    @Test
    fun `un chemin vide est refusé`() {
        assertNotNull(TemplatePathGuard.valider(""))
        assertNotNull(TemplatePathGuard.valider("   "))
    }

    @Test
    fun `un chemin absolu est refusé`() {
        assertNotNull(TemplatePathGuard.valider("/etc/passwd"))
    }

    @Test
    fun `une lettre de lecteur Windows est refusée`() {
        assertNotNull(TemplatePathGuard.valider("C:/Windows/System32"))
        assertNotNull(TemplatePathGuard.valider("c:system"))
    }

    @Test
    fun `un antislash est refusé comme séparateur`() {
        assertNotNull(TemplatePathGuard.valider("src\\main\\Main.txt"))
    }

    @Test
    fun `une remontée de répertoire est refusée`() {
        assertNotNull(TemplatePathGuard.valider("../secret.txt"))
        assertNotNull(TemplatePathGuard.valider("src/../../secret.txt"))
        assertNotNull(TemplatePathGuard.valider("a/b/../../../c.txt"))
    }

    @Test
    fun `un segment point simple est refusé`() {
        assertNotNull(TemplatePathGuard.valider("./main.txt"))
        assertNotNull(TemplatePathGuard.valider("src/./main.txt"))
    }

    @Test
    fun `un segment vide est refusé`() {
        assertNotNull(TemplatePathGuard.valider("src//main.txt"))
        assertNotNull(TemplatePathGuard.valider("src/"))
    }

    @Test
    fun `un caractère de contrôle est refusé`() {
        assertNotNull(TemplatePathGuard.valider("src/ma\u0000in.txt"))
        assertNotNull(TemplatePathGuard.valider("src/ma\u001Fin.txt"))
    }

    @Test
    fun `les espaces de bord d un segment sont refusés`() {
        assertNotNull(TemplatePathGuard.valider(" src/main.txt"))
        assertNotNull(TemplatePathGuard.valider("src /main.txt"))
        assertNotNull(TemplatePathGuard.valider("src/ main.txt"))
    }

    @Test
    fun `un segment se terminant par un point est refusé`() {
        assertNotNull(TemplatePathGuard.valider("dossier./main.txt"))
    }

    @Test
    fun `les noms réservés Windows sont refusés`() {
        assertNotNull(TemplatePathGuard.valider("CON"))
        assertNotNull(TemplatePathGuard.valider("con.txt"))
        assertNotNull(TemplatePathGuard.valider("src/NUL.txt"))
        assertNotNull(TemplatePathGuard.valider("COM1"))
        assertNotNull(TemplatePathGuard.valider("lpt9/data.bin"))
    }

    @Test
    fun `un nom de fichier avec extension normale est sûr`() {
        assertNull(TemplatePathGuard.valider("constants.txt"))
        assertNull(TemplatePathGuard.valider("src/Consumer.java"))
    }

    @Test
    fun `un chemin trop long est refusé`() {
        // Segments courts, chemin total à la limite exacte (240) puis au-delà.
        val segments = { n: Int -> ("b".repeat(80) + "/").repeat(2) + "b".repeat(n) }
        assertNull(TemplatePathGuard.valider(segments(TemplatePathGuard.LONGUEUR_CHEMIN_MAX - 162)))
        assertNotNull(TemplatePathGuard.valider(segments(TemplatePathGuard.LONGUEUR_CHEMIN_MAX - 161)))
    }

    @Test
    fun `un segment trop long est refusé`() {
        val long = "src/" + "b".repeat(TemplatePathGuard.LONGUEUR_SEGMENT_MAX + 1) + ".txt"
        assertNotNull(TemplatePathGuard.valider(long))
    }

    @Test
    fun `le message d erreur cite le chemin fautif`() {
        val probleme = TemplatePathGuard.valider("../x")
        assertNotNull(probleme)
        assertEquals(true, probleme!!.contains(".."))
    }

    @Test
    fun `les doublons sont détectés à la casse près`() {
        assertNull(TemplatePathGuard.verifierDoublons(listOf("README.md", "src/Main.txt")))
        assertEquals(
            "chemin en double dans le plan : « readme.md »",
            TemplatePathGuard.verifierDoublons(listOf("README.md", "readme.md")),
        )
        assertNotNull(TemplatePathGuard.verifierDoublons(listOf("a/b.txt", "A/B.TXT")))
    }
}
