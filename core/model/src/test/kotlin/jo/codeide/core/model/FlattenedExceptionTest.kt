package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de [FlattenedException] : bornes de troncature (tranches et causes),
 * tolérance aux chaînes cycliques et transformation des messages.
 */
class FlattenedExceptionTest {
    /** Exception de test sans pile, pour des données petites et lisibles. */
    private fun exceptionSansPile(message: String): RuntimeException =
        RuntimeException(message).apply { stackTrace = emptyArray() }

    /** Profondeur de la chaîne des causes d'une exception aplatie. */
    private fun profondeur(exception: FlattenedException): Int {
        var niveau = 0
        var curseur: FlattenedException? = exception
        while (curseur?.cause != null) {
            niveau++
            curseur = curseur.cause
        }
        return niveau
    }

    @Test
    fun `aplatit le nom, le message et les tranches de pile`() {
        val exception = IllegalStateException("état incohérent")

        val aplatie = FlattenedException.from(exception)

        assertEquals("java.lang.IllegalStateException", aplatie.className)
        assertEquals("état incohérent", aplatie.message)
        assertTrue(aplatie.frames.isNotEmpty())
        assertTrue(aplatie.frames.first().contains("FlattenedExceptionTest"))
    }

    @Test
    fun `tronque la pile au maximum demandé`() {
        val pile = List(80) { StackTraceElement("Classe", "methode", "Fichier.kt", it + 1) }
        val exception = RuntimeException("bof").apply { stackTrace = pile.toTypedArray() }

        val aplatie = FlattenedException.from(exception, maxFrames = 30)

        assertEquals(30, aplatie.frames.size)
        // Ce sont bien les premières tranches qui sont conservées.
        assertTrue(aplatie.frames.first().endsWith(":1)"))
        assertTrue(aplatie.frames.last().endsWith(":30)"))
    }

    @Test
    fun `suit la chaîne des causes en respectant la profondeur maximale`() {
        var racine: Throwable = exceptionSansPile("niveau 0")
        repeat(20) { profondeur ->
            racine = exceptionSansPile("niveau ${profondeur + 1}").initCause(racine)
        }

        val aplatie = FlattenedException.from(racine, maxCauses = 5)

        assertEquals(5, profondeur(aplatie))
    }

    @Test
    fun `une chaîne de causes cyclique s'aplatit sans boucler`() {
        val a = exceptionSansPile("a")
        val b = exceptionSansPile("b").initCause(a)
        a.initCause(b)

        val aplatie = FlattenedException.from(a)

        // La borne de causes garantit la terminaison : la chaîne est finie.
        assertEquals(FlattenedException.DEFAULT_MAX_CAUSES, profondeur(aplatie))
    }

    @Test
    fun `un message nul est conservé nul`() {
        val aplatie = FlattenedException.from(RuntimeException())

        assertNull(aplatie.message)
        assertNull(aplatie.cause)
    }

    @Test
    fun `transformMessages applique la transformation à toute la chaîne`() {
        val cause = exceptionSansPile("secret@exemple.fr")
        val tete = exceptionSansPile("/storage/emulated/0/x").initCause(cause)
        val aplatie = FlattenedException.from(tete)

        val transformee = aplatie.transformMessages { it?.uppercase() }

        assertEquals("/STORAGE/EMULATED/0/X", transformee.message)
        assertEquals("SECRET@EXEMPLE.FR", transformee.cause?.message)
        // La structure est inchangée : mêmes classes, mêmes tranches.
        assertEquals(aplatie.className, transformee.className)
        assertEquals(aplatie.frames, transformee.frames)
    }

    @Test
    fun `les exceptions supprimées sont aplaties et conservées`() {
        val exception =
            exceptionSansPile("principale").apply {
                addSuppressed(exceptionSansPile("supprimée 1"))
                addSuppressed(exceptionSansPile("supprimée 2"))
            }

        val aplatie = FlattenedException.from(exception)

        assertEquals(2, aplatie.suppressed.size)
        assertEquals("supprimée 1", aplatie.suppressed[0].message)
        assertEquals("supprimée 2", aplatie.suppressed[1].message)
        // Une exception sans cause ni supprimées n'en porte pas.
        assertTrue(aplatie.suppressed[0].suppressed.isEmpty())
    }

    @Test
    fun `les exceptions supprimées sont bornées par niveau`() {
        val exception =
            exceptionSansPile("principale").apply {
                repeat(8) { indice -> addSuppressed(exceptionSansPile("supprimée $indice")) }
            }

        val aplatie = FlattenedException.from(exception)

        assertEquals(FlattenedException.DEFAULT_MAX_SUPPRESSED, aplatie.suppressed.size)
        // Ce sont les premières ajoutées qui sont conservées.
        assertEquals("supprimée 0", aplatie.suppressed.first().message)
    }

    @Test
    fun `une exception sans supprimées porte une liste vide`() {
        val aplatie = FlattenedException.from(exceptionSansPile("seule"))

        assertTrue(aplatie.suppressed.isEmpty())
    }

    @Test
    fun `transformMessages transforme aussi les messages supprimés`() {
        val exception =
            exceptionSansPile("tete").apply {
                addSuppressed(exceptionSansPile("secret@exemple.fr"))
            }
        val aplatie = FlattenedException.from(exception)

        val transformee = aplatie.transformMessages { it?.uppercase() }

        assertEquals("SECRET@EXEMPLE.FR", transformee.suppressed.single().message)
    }
}
