package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le LECTEUR D'ATTRIBUTS (PE12b — `docs/PREVIEW_PARENTS.md` § 4.1) :
 * l'ordre du document, les liens de parenté, les spans sources (même
 * sémantique épinglée que l'aligneur), les paramètres de layout sous
 * clé canonique, les sémantiques d'éléments (merge, include) et la
 * TOLÉRANCE aux documents interrompus.
 *
 * Comme [AligneurElementsXmlTest] : sous Robolectric, qui fournit la
 * VRAIE implémentation d'`XmlPullParser` en JVM (la fabrique de
 * l'android.jar de test n'est pas mockée).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ArbreAttributsXmlTest {
    @Test
    fun `lire - l'ordre du document et les liens de parente`() {
        val noeuds = ArbreAttributsXml.lire(TEXTE_IMBRIQUE)
        assertEquals(
            listOf("LinearLayout", "TextView", "FrameLayout", "Button"),
            noeuds.map { it.balise },
        )
        // Pré-ordre : la racine à 0, chaque nœud connaît son parent.
        assertEquals(listOf(-1, 0, 0, 2), noeuds.map { it.indiceParent })
        // La racine n'a pas d'identifiant ; « titre » est celui du TextView.
        assertEquals(listOf(null, "titre", null, "bouton"), noeuds.map { it.id })
        // L'arbre : les enfants de la racine, puis le bouton dans son cadre.
        assertEquals(2, noeuds[0].enfants.size)
        assertEquals(noeuds[1], noeuds[0].enfants[0])
        assertEquals(noeuds[2], noeuds[0].enfants[1])
        assertEquals(noeuds[3], noeuds[2].enfants.single())
    }

    @Test
    fun `lire - les spans couvrent l'element exact`() {
        val texte = document("<TextView android:id=\"@+id/titre\"/>")
        val noeuds = ArbreAttributsXml.lire(texte)
        assertEquals(2, noeuds.size)
        assertEquals(
            "<TextView android:id=\"@+id/titre\"/>",
            texte.substring(noeuds[1].debut, noeuds[1].fin),
        )
        // La racine couvre TOUT l'élément, ouvrant à fermant.
        assertEquals(texte, texte.substring(noeuds[0].debut, noeuds[0].fin))

        val avecCorps = document("<Button android:id=\"@+id/b\">Ok</Button>")
        val bouton = ArbreAttributsXml.lire(avecCorps)[1]
        assertEquals("<Button android:id=\"@+id/b\">Ok</Button>", avecCorps.substring(bouton.debut, bouton.fin))
    }

    @Test
    fun `lire - les parametres de layout seulement, cles canoniques`() {
        val texte =
            document(
                "<Button android:layout_below=\"@id/b\" app:layout_anchor=\"@id/x\" " +
                    "android:layout_gravity=\"end|bottom\" android:text=\"Ok\" app:title=\"@string/titre\"/>",
            )
        val bouton = ArbreAttributsXml.lire(texte)[1]
        assertEquals(
            linkedMapOf(
                "android:layout_below" to "@id/b",
                "app:layout_anchor" to "@id/x",
                "android:layout_gravity" to "end|bottom",
            ),
            bouton.attributs,
        )
    }

    @Test
    fun `lire - un prefixe exotique sur le namespace android reste canonique`() {
        val texte =
            "<FrameLayout xmlns:a=\"http://schemas.android.com/apk/res/android\">" +
                "<Button a:layout_weight=\"1\"/></FrameLayout>"
        val bouton = ArbreAttributsXml.lire(texte)[1]
        assertEquals(linkedMapOf("android:layout_weight" to "1"), bouton.attributs)
    }

    @Test
    fun `lire - les attributs hors namespaces android et app ne sont pas des parametres`() {
        val texte =
            "<FrameLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "xmlns:app=\"http://schemas.android.com/apk/res/auto\" " +
                "xmlns:tools=\"http://schemas.android.com/tools\" " +
                "tools:layout_editor_absoluteX=\"8dp\" app:menu=\"@menu/x\"/>"
        assertTrue(ArbreAttributsXml.lire(texte)[0].attributs.isEmpty())
    }

    @Test
    fun `lire - l'identifiant se lit sous ses deux formes seulement`() {
        val texte =
            document(
                "<Button android:id=\"@+id/bio\"/>" +
                    "<Button android:id=\"@id/vieux\"/>" +
                    "<Button/>" +
                    "<Button android:id=\"@android:id/list\"/>" +
                    "<Button android:id=\"bio\"/>",
            )
        assertEquals(
            listOf("bio", "vieux", null, null, null),
            ArbreAttributsXml.lire(texte).drop(1).map { it.id },
        )
    }

    @Test
    fun `lire - le merge est transparent`() {
        val racine =
            "<merge xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
                "<Button/><TextView/></merge>"
        val noeuds = ArbreAttributsXml.lire(racine)
        assertEquals(listOf("Button", "TextView"), noeuds.map { it.balise })
        assertEquals(listOf(-1, -1), noeuds.map { it.indiceParent })

        // Un merge INTERNE rattache aussi ses enfants au parent.
        val imbrique =
            document("<merge><Button/></merge><TextView/>")
        val noeudsImbriques = ArbreAttributsXml.lire(imbrique)
        assertEquals(
            listOf("LinearLayout", "Button", "TextView"),
            noeudsImbriques.map { it.balise },
        )
        assertEquals(listOf(-1, 0, 0), noeudsImbriques.map { it.indiceParent })
    }

    @Test
    fun `lire - include requestFocus et blink ne produisent rien`() {
        val texte =
            document(
                "<include layout=\"@layout/autre\"/>" +
                    "<requestFocus/><blink/><TextView/>",
            )
        val noeuds = ArbreAttributsXml.lire(texte)
        assertEquals(listOf("LinearLayout", "TextView"), noeuds.map { it.balise })
        assertEquals(listOf(-1, 0), noeuds.map { it.indiceParent })
    }

    @Test
    fun `lire - un document interrompu se fige sans exception`() {
        // La fermeture manque : la racine reste lue, fin inconnue (-1),
        // l'enfant fermé garde son span.
        val tronque =
            "<RelativeLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
                "<Button android:id=\"@+id/a\"/></RelativeLayout"
        val noeuds = ArbreAttributsXml.lire(tronque)
        assertEquals(listOf("RelativeLayout", "Button"), noeuds.map { it.balise })
        assertEquals(-1, noeuds[0].fin)
        assertTrue(noeuds[1].fin > 0)

        // Interrompu DANS un tag : seul l'élément déjà ouvert survit.
        val interruption =
            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
                "<Button android:id="
        val noeudsInterruption = ArbreAttributsXml.lire(interruption)
        assertEquals(listOf("LinearLayout"), noeudsInterruption.map { it.balise })
        assertEquals(-1, noeudsInterruption[0].fin)
    }

    @Test
    fun `lire - un texte etranger au xml ou vide donne une liste vide`() {
        assertTrue(ArbreAttributsXml.lire("pas du tout du xml").isEmpty())
        assertTrue(ArbreAttributsXml.lire("").isEmpty())
    }

    @Test
    fun `lire - prologue et commentaires ne comptent pas comme elements`() {
        val texte = "<?xml version=\"1.0\"?><!-- une note --><Button/>"
        val noeuds = ArbreAttributsXml.lire(texte)
        assertEquals(listOf("Button"), noeuds.map { it.balise })
    }

    private fun document(corps: String): String =
        "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
            "xmlns:app=\"http://schemas.android.com/apk/res/auto\">$corps</LinearLayout>"

    private companion object {
        private const val TEXTE_IMBRIQUE: String =
            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "xmlns:app=\"http://schemas.android.com/apk/res/auto\">\n" +
                "  <TextView android:id=\"@+id/titre\"/>\n" +
                "  <FrameLayout>\n" +
                "    <Button android:id=\"@id/bouton\" android:layout_gravity=\"center\"/>\n" +
                "  </FrameLayout>\n" +
                "</LinearLayout>"
    }
}
