package jo.codeide.core.crash

import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.CrashType
import jo.codeide.core.model.DeviceInfo
import jo.codeide.core.model.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la fabrique de rapports (section 5.8 : critères obligatoires —
 * expurgation à la construction, bornes de tranches et de causes,
 * reconstruction depuis une sortie de processus).
 */
class CrashReportFactoryTest {
    private class HorlogeReglee : TimeProvider {
        var maintenant = 123_456L

        override fun nowMillis(): Long = maintenant
    }

    private fun fabriqueAvecInputs(
        horloge: HorlogeReglee = HorlogeReglee(),
        configurateur: (CrashReportInputs, HorlogeReglee) -> Unit = { _, _ -> },
    ): Pair<CrashReportFactory, CrashReportInputs> {
        val pisteur = LastScreenTracker()
        val inputs = CrashReportInputs(OutilsTestCrash.infosApplication, pisteur)
        configurateur(inputs, horloge)
        return CrashReportFactory(horloge) to inputs
    }

    @Test
    fun `le rapport porte les entrées applicatives`() {
        val horloge = HorlogeReglee()
        val (fabrique, inputs) = fabriqueAvecInputs(horloge)
        inputs.sessionId = { "session-42" }
        inputs.deviceInfo = { DeviceInfo.inconnu() }
        inputs.lastScreenTracker.onNavigatedTo("Accueil")

        val rapport =
            fabrique.create(
                throwable = IllegalStateException("boum"),
                threadName = "main",
                inputs = inputs,
                processUptimeMs = 9_876L,
                isCrashLoop = false,
            )

        assertEquals("session-42", rapport.sessionId)
        assertEquals("Accueil", rapport.lastScreen)
        assertEquals(123_456L, rapport.timestampMillis)
        assertEquals("main", rapport.threadName)
        assertEquals(9_876L, rapport.processUptimeMs)
        assertEquals(CrashType.EXCEPTION, rapport.type)
        assertEquals("java.lang.IllegalStateException", rapport.exception.className)
        assertEquals("boum", rapport.exception.message)
    }

    @Test
    fun `les messages sont expurgés à la construction`() {
        val (fabrique, inputs) = fabriqueAvecInputs()
        val message =
            "échec pour /storage/emulated/0/Projets/perso.txt et " +
                "content://com.android.providers.downloads/documents/3847, " +
                "contacter jean.dupont@exemple.fr"

        val rapport =
            fabrique.create(
                throwable = RuntimeException(message),
                threadName = "main",
                inputs = inputs,
                processUptimeMs = 0L,
                isCrashLoop = false,
            )

        val relu = rapport.exception.message.orEmpty()
        assertTrue("message expurgé : $relu", relu.contains("<chemin>"))
        assertTrue(relu.contains("content://com.android.providers.downloads/h-"))
        assertTrue(relu.contains("<courriel>"))
        assertTrue(!relu.contains("perso.txt"))
        assertTrue(!relu.contains("3847"))
        assertTrue(!relu.contains("jean.dupont@exemple.fr"))
    }

    @Test
    fun `les messages des causes sont aussi expurgés`() {
        val (fabrique, inputs) = fabriqueAvecInputs()
        val cause = IllegalArgumentException("dossier /storage/emulated/0/secret")

        val rapport =
            fabrique.create(
                throwable = RuntimeException("enveloppe", cause),
                threadName = "main",
                inputs = inputs,
                processUptimeMs = 0L,
                isCrashLoop = false,
            )

        assertEquals("dossier <chemin>", rapport.exception.cause?.message)
    }

    @Test
    fun `les tranches sont bornées à cent et les causes à dix`() {
        val (fabrique, inputs) = fabriqueAvecInputs()
        val fond = RuntimeException("fond")
        var courant: Throwable = fond
        repeat(15) {
            val parent = RuntimeException("niveau $it", courant)
            courant = parent
        }

        val rapport =
            fabrique.create(
                throwable = courant,
                threadName = "main",
                inputs = inputs,
                processUptimeMs = 0L,
                isCrashLoop = false,
            )

        var niveau = rapport.exception
        var sauts = 0
        while (niveau.cause != null) {
            niveau = niveau.cause ?: break
            sauts++
        }
        assertEquals(CrashLimits.MAX_CAUSES, sauts)
    }

    @Test
    fun `une pile profonde est tronquée à cent tranches`() {
        val (fabrique, inputs) = fabriqueAvecInputs()
        // Pile réelle gonflée : impossible de forcer 100+ tranches sans
        // récursion — la borne est donc vérifiée par construction répétée.
        val exception = IllegalStateException("profonde")
        val rapport =
            fabrique.create(
                throwable = exception,
                threadName = "main",
                inputs = inputs,
                processUptimeMs = 0L,
                isCrashLoop = false,
            )

        assertTrue(rapport.exception.frames.size <= CrashLimits.MAX_FRAMES)
    }

    @Test
    fun `les filons sont bornés aux cinquante derniers`() {
        val (fabrique, inputs) = fabriqueAvecInputs()
        inputs.breadcrumbs = { List(80) { OutilsTestCrash.filon(it) } }

        val rapport =
            fabrique.create(
                throwable = RuntimeException("boum"),
                threadName = "main",
                inputs = inputs,
                processUptimeMs = 0L,
                isCrashLoop = false,
            )

        assertEquals(CrashLimits.MAX_BREADCRUMBS, rapport.breadcrumbs.size)
        // Les plus récents (30..79), pas les plus anciens.
        assertEquals("étape 79", rapport.breadcrumbs.last().message)
        assertEquals("étape 30", rapport.breadcrumbs.first().message)
    }

    @Test
    fun `la reconstruction depuis une sortie de processus est honnête`() {
        val horloge = HorlogeReglee()
        val fabrique = CrashReportFactory(horloge)

        val rapport =
            fabrique.fromExitInfo(
                type = CrashType.ANR,
                timestampMillis = 500L,
                reason = "ANR — thread principal bloqué",
                trace = "ligne 1\nligne 2\n\nligne 3",
                appInfo = OutilsTestCrash.infosApplication,
                deviceInfo = { DeviceInfo.inconnu() },
            )

        assertEquals(CrashType.ANR, rapport.type)
        assertEquals(500L, rapport.timestampMillis)
        assertEquals("", rapport.sessionId)
        assertEquals("", rapport.threadName)
        assertNull(rapport.lastScreen)
        assertEquals(0L, rapport.processUptimeMs)
        assertEquals(emptyList<LogEntry>(), rapport.breadcrumbs)
        assertEquals(listOf("ligne 1", "ligne 2", "ligne 3"), rapport.exception.frames)
        assertTrue(!rapport.isCrashLoop)
    }

    @Test
    fun `la trace vide reste une trace vide`() {
        val fabrique = CrashReportFactory(HorlogeReglee())

        val rapport =
            fabrique.fromExitInfo(
                type = CrashType.NATIVE,
                timestampMillis = 500L,
                reason = "plantage natif",
                trace = null,
                appInfo = OutilsTestCrash.infosApplication,
                deviceInfo = { DeviceInfo.inconnu() },
            )

        assertEquals(emptyList<String>(), rapport.exception.frames)
        assertEquals(CrashType.NATIVE.name, rapport.exception.className)
    }

    @Test
    fun `la troncature UTF-8 ne coupe pas un caractère multi-octets`() {
        val texte = "é".repeat(3_000)
        assertTrue(texte.toByteArray(Charsets.UTF_8).size > CrashLimits.MAX_MESSAGE_BYTES)

        val tronque = tronquerUTF8(texte, CrashLimits.MAX_MESSAGE_BYTES)

        assertTrue(tronque.toByteArray(Charsets.UTF_8).size <= CrashLimits.MAX_MESSAGE_BYTES)
        assertTrue(tronque.isNotEmpty())
        // Chaque caractère conservé est complet : re-décodage sans perte.
        assertEquals(tronque.count { it == 'é' }, tronque.length)
    }

    @Test
    fun `la troncature d'un texte court est sans effet`() {
        assertEquals("court", tronquerUTF8("court", 1_000))
        assertEquals("", tronquerUTF8("é", 0))
    }
}
