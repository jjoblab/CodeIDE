package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'extraction des emplacements de pile cliquables (mission
 * « Exécuter » R4) : cadres .kt/.java retenus, sources inconnues ignorées,
 * traces multi-lignes, détection de plantage du pont.
 */
class LignesPileTest {
    private fun ligne(
        etiquette: String = "MainActivity",
        message: String = "onCreate terminé",
        niveau: NiveauJournal = NiveauJournal.INFO,
    ): LigneJournal =
        LigneJournal(
            horodatageMs = 1_000L,
            pid = 4321,
            tid = 4321,
            niveau = niveau,
            etiquette = etiquette,
            message = message,
        )

    @Test
    fun `les cadres kotlin et java sont retenus avec classe methode et ligne`() {
        val emplacements =
            LignesPile.extraire(
                "java.lang.IllegalStateException: état cassé\n" +
                    "\tat com.exemple.MainActivity.onCreate(MainActivity.kt:12)\n" +
                    "\tat android.app.Activity.performCreate(Activity.java:9000)",
            )

        assertEquals(2, emplacements.size)
        val premier = emplacements[0]
        assertEquals("com.exemple.MainActivity", premier.classe)
        assertEquals("onCreate", premier.methode)
        assertEquals("MainActivity.kt", premier.fichier)
        assertEquals(12, premier.ligne)
        assertEquals("Activity.java", emplacements[1].fichier)
    }

    @Test
    fun `les sources inconnues et methodes natives sont ignorees`() {
        val emplacements =
            LignesPile.extraire(
                "\tat com.exemple.A.a(Unknown Source)\n" +
                    "\tat com.exemple.B.b(Native Method)\n" +
                    "\tat com.exemple.C.c(C.kt:3)",
            )

        assertEquals(listOf("C.kt"), emplacements.map { it.fichier })
    }

    @Test
    fun `les fichiers non editables sont ignores`() {
        val emplacements =
            LignesPile.extraire(
                "\tat com.exemple.D.d(D.png:7)\n" +
                    "\tat com.exemple.E.e(E.jar:8)",
            )

        assertTrue(emplacements.isEmpty())
    }

    @Test
    fun `un message sans pile ne donne rien`() {
        assertTrue(LignesPile.extraire("onCreate terminé en 412 ms").isEmpty())
        assertTrue(LignesPile.extraire("").isEmpty())
    }

    @Test
    fun `la trace complete du plantage du pont est extraite en ordre`() {
        val trace =
            "java.lang.RuntimeException: boom\n" +
                "\tat com.exemple.ui.Vue.afficher(Vue.kt:48)\n" +
                "\tat com.exemple.ui.Ecran.onResume(Ecran.kt:27)\n" +
                "\tat android.app.Instrumentation.callActivityOnResume(Instrumentation.java:1454)"
        val emplacements = LignesPile.extraire(trace)

        assertEquals(3, emplacements.size)
        assertEquals("Vue.kt:48", "${emplacements[0].fichier}:${emplacements[0].ligne}")
        assertEquals("Ecran.kt:27", "${emplacements[1].fichier}:${emplacements[1].ligne}")
    }

    @Test
    fun `les lignes non positives sont rejetees`() {
        assertTrue(LignesPile.extraire("\tat com.exemple.F.f(F.kt:0)").isEmpty())
    }

    @Test
    fun `est plantage reconnait l annonce du pont`() {
        assertTrue(
            LignesPile.estPlantage(
                ligne(
                    etiquette = "Plantage",
                    message = "Exception non interceptée dans le fil « main »",
                    niveau = NiveauJournal.ERREUR,
                ),
            ),
        )
        // Pas un plantage : autre étiquette, ou message simple.
        assertFalse(LignesPile.estPlantage(ligne(etiquette = "MainActivity", message = "Exception non interceptée")))
        assertFalse(LignesPile.estPlantage(ligne(etiquette = "Plantage", message = "java.lang.RuntimeException: boom")))
    }
}
