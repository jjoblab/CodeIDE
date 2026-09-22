package jo.codeide.core.crash

import jo.codeide.core.model.CrashType
import jo.codeide.core.model.FlattenedException
import jo.codeide.core.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du mapping JSON des rapports (section 5.8 : critères obligatoires —
 * construction et sérialisation en **aller-retour**, limites de taille,
 * réduction progressive).
 *
 * Pure JVM : `org.json` du framework est servi par son artefact de test
 * standard, sans Android.
 */
class CrashReportJsonTest {
    /** Vérifie qu'un filon d'exception est porté dans un rapport. */
    private fun rapportAvecException(chaine: FlattenedException) = OutilsTestCrash.rapport().copy(exception = chaine)

    @Test
    fun `l'aller-retour complet préserve toutes les sections`() {
        val original = OutilsTestCrash.rapport(id = "aller-retour", horodatage = 45_678L, boucle = true)

        val relu =
            CrashReportJson.lire(
                CrashReportJson.ecrire(original, CrashReportJson.Reduction.COMPLET).toString(),
            )

        assertEquals(original.id, relu?.id)
        assertEquals(original.type, relu?.type)
        assertEquals(original.timestampMillis, relu?.timestampMillis)
        assertEquals(original.sessionId, relu?.sessionId)
        assertEquals(original.application, relu?.application)
        assertEquals(original.device, relu?.device)
        assertEquals(original.threadName, relu?.threadName)
        assertEquals(original.exception, relu?.exception)
        assertEquals(original.breadcrumbs, relu?.breadcrumbs)
        assertEquals(original.lastScreen, relu?.lastScreen)
        assertEquals(original.processUptimeMs, relu?.processUptimeMs)
        assertEquals(original.isCrashLoop, relu?.isCrashLoop)
    }

    @Test
    fun `l'aller-retour traverse la chaîne de causes et les supprimées`() {
        val chaine =
            FlattenedException(
                className = "java.lang.RuntimeException",
                message = "enveloppe",
                frames = listOf("A.un(B.kt:1)", "B.deux(C.kt:2)"),
                cause =
                    FlattenedException(
                        className = "java.lang.IllegalArgumentException",
                        message = "cause",
                        frames = listOf("C.trois(D.kt:3)"),
                        cause = null,
                    ),
                suppressed =
                    listOf(
                        FlattenedException("java.io.IOException", "supprimée", emptyList(), null),
                    ),
            )
        val original = rapportAvecException(chaine)

        val relu =
            CrashReportJson.lire(
                CrashReportJson.ecrire(original, CrashReportJson.Reduction.COMPLET).toString(),
            )

        assertEquals(chaine, relu?.exception)
    }

    @Test
    fun `un filon avec exception traverse l'aller-retour`() {
        val exceptionFilon = FlattenedException("java.io.IOException", "entrée-sortie", emptyList(), null)
        val original =
            OutilsTestCrash.rapport(
                filons =
                    listOf(
                        jo.codeide.core.model.LogEntry(
                            timestampMillis = 9L,
                            sessionId = "session-1",
                            level = LogLevel.ERROR,
                            tag = "Test",
                            threadName = "DefaultDispatcher-worker-1",
                            message = "échec",
                            exception = exceptionFilon,
                        ),
                    ),
            )

        val relu =
            CrashReportJson.lire(
                CrashReportJson.ecrire(original, CrashReportJson.Reduction.COMPLET).toString(),
            )

        assertEquals(exceptionFilon, relu?.breadcrumbs?.single()?.exception)
    }

    @Test
    fun `la réduction coupe les filons puis la trace sans changer la structure`() {
        val original =
            OutilsTestCrash.rapport(
                filons = List(20) { OutilsTestCrash.filon(it) },
                id = "reduit",
            )

        val jsonReduit = CrashReportJson.ecrire(original, CrashReportJson.Reduction.REDUIT)

        assertEquals(10, jsonReduit.getJSONArray("breadcrumbs").length())
        val relu = CrashReportJson.lire(jsonReduit.toString())
        assertEquals(10, relu?.breadcrumbs?.size)
        assertEquals(original.id, relu?.id)

        val jsonMaigre = CrashReportJson.ecrire(original, CrashReportJson.Reduction.MAIGRE)
        assertEquals(0, CrashReportJson.lire(jsonMaigre.toString())?.breadcrumbs?.size)

        val jsonMinimal = CrashReportJson.ecrire(original, CrashReportJson.Reduction.MINIMAL)
        assertTrue(jsonMinimal.getJSONObject("exception").getJSONArray("frames").length() == 0)
    }

    @Test
    fun `le résumé extrait l'essentiel sans lire le rapport entier`() {
        val rapport = OutilsTestCrash.rapport(id = "resume", horodatage = 777L, type = CrashType.ANR)

        val resume =
            CrashReportJson.lireResume(
                CrashReportJson.ecrire(rapport, CrashReportJson.Reduction.COMPLET).toString(),
                consulte = true,
            )

        assertEquals("resume", resume?.id)
        assertEquals(777L, resume?.timestampMillis)
        assertEquals(CrashType.ANR, resume?.type)
        assertEquals("IllegalStateException", resume?.exceptionClassName)
        assertEquals("boum resume", resume?.shortMessage)
        assertTrue(resume?.isReviewed == true)
    }

    @Test
    fun `un contenu invalide rend un rapport nul sans lever`() {
        assertNull(CrashReportJson.lire("{pas du json"))
        assertNull(CrashReportJson.lire("""{"autre":"cle"}"""))
        assertNull(CrashReportJson.lireResume("null", consulte = false))
    }

    @Test
    fun `un type inconnu retombe sur EXCEPTION à la lecture`() {
        val json = CrashReportJson.ecrire(OutilsTestCrash.rapport(), CrashReportJson.Reduction.MINIMAL)
        json.put("type", "INCONNU")

        assertEquals(CrashType.EXCEPTION, CrashReportJson.lire(json.toString())?.type)
    }
}
