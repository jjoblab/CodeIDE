package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests de l'empreinte de chaîne d'outils et de son détecteur de
 * changement (§ 6, E4 — ADR 0089) : le daemon Gradle est relancé quand
 * l'empreinte change, jamais à la première observation.
 */
class EmpreinteChaineOutilsTest {
    @Test
    fun `l empreinte est stable pour les mêmes entrées`() {
        val empreinte =
            EmpreinteChaineOutils.calculer(
                javaHome = File("/prefix/usr/lib/jvm/java-17-openjdk"),
                androidHome = File("/home/android-sdk"),
                aapt2 = File("/home/android-sdk/build-tools/35.0.2/aapt2"),
                versions = mapOf("jdk" to "17.0.20", "build-tools" to "35.0.2"),
            )
        val identique =
            EmpreinteChaineOutils.calculer(
                javaHome = File("/prefix/usr/lib/jvm/java-17-openjdk"),
                androidHome = File("/home/android-sdk"),
                aapt2 = File("/home/android-sdk/build-tools/35.0.2/aapt2"),
                versions = mapOf("jdk" to "17.0.20", "build-tools" to "35.0.2"),
            )
        assertEquals(identique, empreinte)
        assertEquals(64, empreinte.length)
    }

    @Test
    fun `l ordre des versions ne change pas l empreinte`() {
        val a =
            EmpreinteChaineOutils.calculer(null, null, null, versions = linkedMapOf("a" to "1", "b" to "2"))
        val b =
            EmpreinteChaineOutils.calculer(null, null, null, versions = linkedMapOf("b" to "2", "a" to "1"))
        assertEquals(a, b)
    }

    @Test
    fun `chaque entrée distingue l empreinte`() {
        val base =
            EmpreinteChaineOutils.calculer(
                javaHome = File("/jvm"),
                androidHome = File("/sdk"),
                aapt2 = File("/sdk/aapt2"),
                versions = mapOf("jdk" to "17"),
            )
        assertNotEquals(
            base,
            EmpreinteChaineOutils.calculer(
                File("/autre-jvm"),
                File("/sdk"),
                File("/sdk/aapt2"),
                mapOf("jdk" to "17"),
            ),
        )
        assertNotEquals(
            base,
            EmpreinteChaineOutils.calculer(
                File("/jvm"),
                File("/autre-sdk"),
                File("/sdk/aapt2"),
                mapOf("jdk" to "17"),
            ),
        )
        assertNotEquals(
            base,
            EmpreinteChaineOutils.calculer(
                File("/jvm"),
                File("/sdk"),
                File("/sdk/autre-aapt2"),
                mapOf("jdk" to "17"),
            ),
        )
        assertNotEquals(
            base,
            EmpreinteChaineOutils.calculer(
                File("/jvm"),
                File("/sdk"),
                File("/sdk/aapt2"),
                mapOf("jdk" to "21"),
            ),
        )
    }

    @Test
    fun `le détecteur ne signale pas la première observation mais chaque changement ensuite`() {
        val detecteur = DetecteurChangementEmpreinte()

        assertFalse("la première observation n'est pas un changement", detecteur.traiter("aaa"))
        assertTrue("un changement est signalé", detecteur.traiter("bbb"))
        assertFalse("la même empreinte ne signale rien", detecteur.traiter("bbb"))
        assertTrue("un retour arrière est un changement", detecteur.traiter("aaa"))
    }
}
