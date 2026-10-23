package jo.codeide.preview.xml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le REDIMENSIONNEMENT PAR POIGNÉE de la vue de preview (PE11l, demandé par
 * le propriétaire ; PE14-S2 ADR 0138 : compensation de préhension C1 +
 * fantôme persistant C4) — extraits de [VuePreviewXmlTest] (découpage
 * detekt LargeClass, zéro exemption — règle du projet) : la machine
 * [VuePreviewXml.traiterRedimension] est testée SANS [MotionEvent] réelle,
 * une écriture par geste, la compensation rature le « saut de préhension ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class VuePreviewXmlRedimensionnementTest {
    private fun vue(): VuePreviewXml = vueDeTest()

    private fun vues(): List<InfoVueRendu> = arbreVuesDeTest()

    @Test
    fun `redimensionnement - saisie fantome puis UNE ecriture au relachement`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        val sortie = Bitmap.createBitmap(512, 1024, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sortie)
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        vue.surlignerVue(1)
        var ecritures = 0
        var cible = 0 to 0
        vue.auRedimensionner = { cibleRecue ->
            ecritures += 1
            cible = cibleRecue.largeurDp to cibleRecue.hauteurDp
            true
        }
        // Fit d'une planche 100×200 dans 512×1024 : échelle 5,12, décalages 0.
        // Saisie de la poignée BAS-DROITE de la vue 1 (10,10 → 90,60) :
        // coin (90, 60), ancre OPPOSÉE (10, 10) — l'événement est CONSOMMÉ.
        assertTrue(
            "saisie consommée",
            vue.traiterRedimension(MotionEvent.ACTION_DOWN, 90f * ECHELLE_FIT, 60f * ECHELLE_FIT),
        )
        // Le doigt suit : le fantôme se dessine SANS lever (rien n'est écrit).
        assertTrue(
            "suivi consommé",
            vue.traiterRedimension(MotionEvent.ACTION_MOVE, 50f * ECHELLE_FIT, 80f * ECHELLE_FIT),
        )
        vue.draw(canvas)
        assertEquals("rien n'est écrit avant le relâchement", 0, ecritures)
        // Relâchement : UNE écriture groupée — cible (50, 80) depuis l'ancre
        // (10, 10) : 40 px → 15 dp, 70 px → 25 dp (440 dpi).
        assertTrue(
            "relâchement consommé",
            vue.traiterRedimension(MotionEvent.ACTION_UP, 50f * ECHELLE_FIT, 80f * ECHELLE_FIT),
        )
        assertEquals("UNE écriture par geste", 1, ecritures)
        assertEquals(15 to 25, cible)
    }

    @Test
    fun `redimensionnement - contact sans derive et saisie hors poignee`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        vue.surlignerVue(1)
        var ecritures = 0
        vue.auRedimensionner = { _ ->
            ecritures += 1
            true
        }
        // Hors poignée (centre de la vue) : le geste n'est PAS consommé —
        // pan/zoom/tap gardent la main.
        assertFalse(
            "centre de la vue : pas de saisie",
            vue.traiterRedimension(MotionEvent.ACTION_DOWN, 50f * ECHELLE_FIT, 35f * ECHELLE_FIT),
        )
        // Contact sur la poignée SANS dérive : consommé mais RIEN n'est
        // écrit au relâchement (miroir le tap sans dérive du canvas).
        assertTrue(
            "poignée saisie",
            vue.traiterRedimension(MotionEvent.ACTION_DOWN, 90f * ECHELLE_FIT, 60f * ECHELLE_FIT),
        )
        assertTrue(
            "relâchement consommé",
            vue.traiterRedimension(MotionEvent.ACTION_UP, 90f * ECHELLE_FIT, 60f * ECHELLE_FIT),
        )
        assertEquals("contact seul : aucune écriture", 0, ecritures)
    }

    @Test
    fun `redimensionnement - sans selection ni densite aucune saisie`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        // Sans rendu du tout : rien à saisir.
        assertFalse(
            "sans bitmap : pas de saisie",
            vue.traiterRedimension(MotionEvent.ACTION_DOWN, 0f, 0f),
        )
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
        )
        vue.surlignerVue(1)
        // Sans densité de profil (repli px) : le geste ne peut pas
        // produire de dp — aucune saisie (pas de faux repli).
        assertFalse(
            "sans densité : pas de saisie",
            vue.traiterRedimension(MotionEvent.ACTION_DOWN, 90f * ECHELLE_FIT, 60f * ECHELLE_FIT),
        )
    }

    // ── PE14-S2 — compensation de préhension (C1) + fantôme persistant (C4) ──

    @Test
    fun `redimensionnement - la saisie décalée ne fait plus sauter la cible`() {
        // § 1.3 de l'étude PE14 : le doigt saisit DANS le rayon de saisie
        // (18 dp écran ≈ 3,5 px contenu à l'échelle du test), PAS sur le
        // coin. Le contact à (+2, +2) du coin bas-droit doit produire la
        // MÊME cible que le contact pile sur le coin : le coin VISUEL
        // suit le doigt (vue 1 : 10,10 → 90,60, ancre haut-gauche).
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        vue.surlignerVue(1)
        var cible: RedimensionApercu.CibleRedimension? = null
        vue.auRedimensionner = { cibleRecue ->
            cible = cibleRecue
            true
        }
        // Contact pris à 2 px au sud-est du coin (dans le rayon de 18 dp
        // écran = 3,5 px contenu) — le geste part de la TAILLE ACTUELLE.
        assertTrue(
            "saisie décalée consommée",
            vue.traiterRedimension(
                MotionEvent.ACTION_DOWN,
                92f * ECHELLE_FIT,
                62f * ECHELLE_FIT,
            ),
        )
        // Le doigt glisse de 6 px vers le sud-est : la cible vaut la
        // DISTANCE ANCRE→(doigt+décalage), PAS ancre→doigt (sinon +2 px
        // par axe de trop — le « saut de préhension » de l'étude : ici
        // 88×58 px = 32×21 dp, DEUX dp de trop par axe).
        assertTrue(
            "suivi consommé",
            vue.traiterRedimension(
                MotionEvent.ACTION_MOVE,
                98f * ECHELLE_FIT,
                68f * ECHELLE_FIT,
            ),
        )
        assertTrue(
            "relâchement consommé",
            vue.traiterRedimension(
                MotionEvent.ACTION_UP,
                98f * ECHELLE_FIT,
                68f * ECHELLE_FIT,
            ),
        )
        val recue = cible
        assertNotNull("UNE écriture au relâchement", recue)
        // Ancre (10,10) → coin visé (98−2, 68−2) = (96, 66) — le contact
        // est au sud-est du coin, la compensation RATURE vers le coin :
        // cible 86 px → 31 dp, 56 px → 20 dp (440 dpi) — PAS 88×58 (+2 px
        // par axe = 32×21, l'ancien comportement).
        assertEquals(31 to 20, recue!!.largeurDp to recue.hauteurDp)
        assertEquals("l'ancre reste le coin opposé", 10 to 10, recue.ancreX to recue.ancreY)
        assertEquals("le coin visé est le doigt COMPENSÉ", 96 to 66, recue.coinX to recue.coinY)
    }

    @Test
    fun `redimensionnement - le fantome persiste jusqu au rendu suivant puis se fond`() {
        // C4 (ADR 0138) : le relâchement GARDE la cible affichée jusqu'au
        // rendu suivant — plus de fenêtre « rien ne s'est passé » pendant
        // le re-rendu asynchrone ; le rendu suivant amorce le fondu (150 ms
        // — la planche nouvelle est déjà posée dessous), la réinitialisation
        // nettoie tout.
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        val planche = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        vue.afficherRendu(planche, vuesRendues = vues(), densiteDpiProfil = 440)
        vue.surlignerVue(1)
        vue.auRedimensionner = { true }
        assertTrue(
            vue.traiterRedimension(MotionEvent.ACTION_DOWN, 90f * ECHELLE_FIT, 60f * ECHELLE_FIT),
        )
        assertTrue(
            vue.traiterRedimension(MotionEvent.ACTION_MOVE, 50f * ECHELLE_FIT, 80f * ECHELLE_FIT),
        )
        assertTrue(
            vue.traiterRedimension(MotionEvent.ACTION_UP, 50f * ECHELLE_FIT, 80f * ECHELLE_FIT),
        )
        assertTrue("la cible reste affichée après le relâchement", vue.fantomePersistantAffiche())
        // Le rendu suivant arrive : le fantôme PERSISTE (le fondu commence,
        // 150 ms — la planche nouvelle est déjà posée dessous).
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        assertTrue("le fantôme se fond, il ne disparaît pas d'un coup", vue.fantomePersistantAffiche())
        vue.reinitialiser()
        assertFalse("la réinitialisation nettoie", vue.fantomePersistantAffiche())
    }

    @Test
    fun `redimensionnement - echec d ecriture et contact sans derive ne posent aucun fantome`() {
        // C4 (ADR 0138) : un fantôme n'est une promesse que si l'écriture a
        // EU LIEU — l'échec efface immédiatement (pas de faux confort), un
        // NOUVEAU geste remplace l'ancien fantôme, un contact sans dérive
        // n'en pose AUCUN.
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        vue.surlignerVue(1)
        vue.auRedimensionner = { false }
        assertTrue(
            vue.traiterRedimension(MotionEvent.ACTION_DOWN, 90f * ECHELLE_FIT, 60f * ECHELLE_FIT),
        )
        assertTrue(
            vue.traiterRedimension(MotionEvent.ACTION_MOVE, 50f * ECHELLE_FIT, 80f * ECHELLE_FIT),
        )
        assertTrue(
            vue.traiterRedimension(MotionEvent.ACTION_UP, 50f * ECHELLE_FIT, 80f * ECHELLE_FIT),
        )
        assertFalse("échec d'écriture : le fantôme ne s'attarde pas", vue.fantomePersistantAffiche())

        // Un NOUVEAU geste remplace l'ancien fantôme, un contact sans
        // dérive n'en pose AUCUN.
        vue.auRedimensionner = { true }
        assertTrue(
            vue.traiterRedimension(MotionEvent.ACTION_DOWN, 90f * ECHELLE_FIT, 60f * ECHELLE_FIT),
        )
        assertTrue(
            vue.traiterRedimension(MotionEvent.ACTION_MOVE, 50f * ECHELLE_FIT, 80f * ECHELLE_FIT),
        )
        assertTrue(
            vue.traiterRedimension(MotionEvent.ACTION_UP, 50f * ECHELLE_FIT, 80f * ECHELLE_FIT),
        )
        assertTrue(vue.fantomePersistantAffiche())
        assertTrue(
            "nouvelle saisie",
            vue.traiterRedimension(MotionEvent.ACTION_DOWN, 90f * ECHELLE_FIT, 60f * ECHELLE_FIT),
        )
        assertFalse("le geste vivant remplace le fantôme", vue.fantomePersistantAffiche())
        assertTrue(
            "relâchement sans dérive",
            vue.traiterRedimension(MotionEvent.ACTION_UP, 90f * ECHELLE_FIT, 60f * ECHELLE_FIT),
        )
        assertFalse("contact seul : aucun fantôme", vue.fantomePersistantAffiche())
    }

    private companion object {
        /** L'échelle du fit d'une planche 100×200 dans une vue 512×1024. */
        private const val ECHELLE_FIT = 5.12f
    }
}
