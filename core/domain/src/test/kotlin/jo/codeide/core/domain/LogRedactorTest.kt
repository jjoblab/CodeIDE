package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de [LogRedactor] — critère d'acceptation de l'étape 2 : URI,
 * chemins et adresses e-mail doivent être masqués, sans déformer le reste
 * du message.
 */
class LogRedactorTest {
    @Test
    fun `masque une adresse e-mail`() {
        assertEquals(
            "contact : <courriel>",
            LogRedactor.redact("contact : jean.dupont@example.fr"),
        )
    }

    @Test
    fun `masque plusieurs e-mails différents dans un même message`() {
        val epure =
            LogRedactor.redact("de a@b.fr vers c.d@e-f.org et g@h.co.uk : refus")

        assertEquals("de <courriel> vers <courriel> et <courriel> : refus", epure)
    }

    @Test
    fun `conserve l'autorité d'une URI content et hache l'identifiant`() {
        val epure =
            LogRedactor.redact(
                "dossier : content://com.android.externalstorage.documents/tree/primary%3AProjects",
            )

        assertTrue(epure.contains("content://com.android.externalstorage.documents/h-"))
        assertFalse(epure.contains("primary%3AProjects"))
        // Le hachage fait exactement 8 caractères hexadécimaux.
        val attendu =
            Regex(
                """^dossier : content://com\.android\.externalstorage\.documents/h-[0-9a-f]{8}$""",
            )
        assertTrue(attendu.matches(epure))
    }

    @Test
    fun `le hachage est deterministe et distingue les identifiants`() {
        val base = "content://autorite/doc/"
        val epureA1 = LogRedactor.redact(base + "aaa")
        val epureA2 = LogRedactor.redact(base + "aaa")
        val epureB = LogRedactor.redact(base + "bbb")

        assertEquals(epureA1, epureA2)
        assertFalse(epureA1 == epureB)
    }

    @Test
    fun `une URI content sans identifiant est conservée telle quelle`() {
        assertEquals(
            "provider : content://autorite.test",
            LogRedactor.redact("provider : content://autorite.test"),
        )
    }

    @Test
    fun `une URI déjà hachée n'est pas re-hashée (idempotence)`() {
        val premiere = LogRedactor.redact("content://a/b/c")

        val seconde = LogRedactor.redact(premiere)

        assertEquals(premiere, seconde)
    }

    @Test
    fun `masque un chemin absolu de stockage`() {
        assertEquals(
            "échec d'écriture dans <chemin>",
            LogRedactor.redact("échec d'écriture dans /storage/emulated/0/CodeIDE/projects"),
        )
    }

    @Test
    fun `masque un chemin absolu de données applicatives`() {
        assertEquals(
            "lecture de <chemin> impossible",
            LogRedactor.redact("lecture de /data/user/0/jo.codeide/files impossible"),
        )
    }

    @Test
    fun `masque une URI file`() {
        assertEquals(
            "source : file://<chemin>",
            LogRedactor.redact("source : file:///storage/emulated/0/Download/x.txt"),
        )
    }

    @Test
    fun `les chemins relatifs ne sont pas masqués`() {
        assertEquals(
            "ouverture de projects/demo/Main.kt",
            LogRedactor.redact("ouverture de projects/demo/Main.kt"),
        )
    }

    @Test
    fun `les URI http ne sont pas déformées`() {
        val original = "voir https://example.com/aide/page et http://x.io/y"

        assertEquals(original, LogRedactor.redact(original))
    }

    @Test
    fun `un slash isolé n'est pas masqué`() {
        assertEquals(
            "vrai/faux",
            LogRedactor.redact("vrai/faux"),
        )
    }

    @Test
    fun `un message réaliste est intégralement expurgé`() {
        val epure =
            LogRedactor.redact(
                "Échec du projet 42 : /storage/emulated/0/Projets/Demo, " +
                    "dossier SAF content://autorite/tree/12ab, prévenir auteur@example.com",
            )

        assertFalse(epure.contains("/storage/emulated"))
        assertFalse(epure.contains("12ab"))
        assertFalse(epure.contains("auteur@example.com"))
        assertTrue(epure.contains("content://autorite/h-"))
        assertTrue(epure.contains("<chemin>"))
        assertTrue(epure.contains("<courriel>"))
        // Le texte utile survit à l'expurgation.
        assertTrue(epure.contains("Échec du projet 42"))
    }

    @Test
    fun `les parentheses autour d'une URI sont conservées`() {
        val epure = LogRedactor.redact("(content://auth/tree/x1)")

        assertEquals("(content://auth/h-${hacher("/tree/x1")})", epure)
    }

    /** Hachage de référence recalculé selon le même algorithme SHA-256 tronqué.
     * Le reste haché commence par le « / » séparateur de l'autorité. */
    private fun hacher(reste: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(reste.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString(separator = "") { "%02x".format(it) }
}
