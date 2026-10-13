package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Alignement éléments XML ↔ vues (PE3, P6) — la moitié SOURCE de la
 * sélection : les SPANS doivent être EXACTS dans le texte original (la
 * sémantique (ligne, colonne) du parseur est épinglée ici même : position
 * juste APRÈS le `>` du tag, début retrouvé par recherche arrière).
 *
 * Miroir des sémantiques de `BridgeInflater` : une vue par élément,
 * `<merge>` transparent, `<include>` résolu (éléments externes non
 * sélectionnables), `<requestFocus>`/`<blink>` sans vue, pré-ordre
 * (parent AVANT enfants).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AligneurElementsXmlTest {
    /** Document de référence : spans calculés à la main. */
    private val document =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent">
            <TextView
                android:id="@+id/titre"
                android:text="Bonjour" />
            <Button
                android:layout_width="wrap_content"
                android:text="Action">
                <requestFocus />
            </Button>
        </LinearLayout>
        """.trimIndent()

    @Test
    fun `spans exacts - pré-ordre, éléments producteurs seulement`() {
        val elements = AligneurElementsXml.aligner(document, null)

        assertEquals("3 éléments producteurs (root + TextView + Button)", 3, elements.size)

        // Pré-ordre : le LinearLayout précède ses enfants.
        assertEquals("LinearLayout", elements[0].nomBalise)
        assertEquals(null, elements[0].idNom)
        // Le span du root démarre à son '<' et se termine après son '>' fermant.
        assertEquals('<', document[elements[0].debut])
        val finRoot = document.indexOf("</LinearLayout>") + "</LinearLayout>".length
        assertEquals(finRoot, elements[0].fin)

        // TextView : span du '<' ouvrant à la fin de la balise auto-fermante.
        assertEquals("TextView", elements[1].nomBalise)
        assertEquals("@+id/titre", elements[1].idNom)
        val debutTextView = document.indexOf("<TextView")
        val finTextView = document.indexOf("/>", debutTextView) + 2
        assertEquals(debutTextView, elements[1].debut)
        assertEquals(finTextView, elements[1].fin)
        assertTrue(
            "le span contient l'attribut id",
            document.substring(elements[1].debut, elements[1].fin).contains("android:id"),
        )

        // Button : requestFocus intérieur IGNORE (pas de vue), span jusqu'au
        // </Button> fermant (la balise fermante couvre le requestFocus).
        assertEquals("Button", elements[2].nomBalise)
        val debutButton = document.indexOf("<Button")
        val finButton = document.indexOf("</Button>") + "</Button>".length
        assertEquals(debutButton, elements[2].debut)
        assertEquals(finButton, elements[2].fin)
    }

    @Test
    fun `merge - transparent (aucun élément, enfants en place)`() {
        val avecMerge =
            """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <merge>
                    <TextView android:text="a" />
                    <TextView android:text="b" />
                </merge>
                <Button android:text="c" />
            </FrameLayout>
            """.trimIndent()
        val elements = AligneurElementsXml.aligner(avecMerge, null)
        // FrameLayout + 2 TextView + Button — le merge lui-même disparaît.
        assertEquals(listOf("FrameLayout", "TextView", "TextView", "Button"), elements.map { it.nomBalise })
    }

    @Test
    fun `include résolu - éléments externes non sélectionnables, séquence conservée`() {
        val inclus =
            """
            <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                android:text="inclus" />
            """.trimIndent()
        val principal =
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <include layout="@layout/enfant" />
                <Button android:text="apres" />
            </LinearLayout>
            """.trimIndent()
        val resolveur =
            AligneurElementsXml.ResolveurLayout { reference ->
                if (reference == "@layout/enfant") inclus else null
            }
        val elements = AligneurElementsXml.aligner(principal, resolveur)

        // Séquence : root, TextView DU FICHIER INCLUS (externe), Button.
        assertEquals(listOf("LinearLayout", "TextView", "Button"), elements.map { it.nomBalise })
        assertTrue("l'élément inclus est externe", elements[1].externe)
        assertEquals("span externe désarmé", -1, elements[1].debut)
        assertEquals(-1, elements[1].fin)
        // Le Button APRÈS l'include reste parfaitement aligné (spans valides).
        val debutButton = principal.indexOf("<Button")
        assertEquals(debutButton, elements[2].debut)
        assertTrue(elements[2].debut >= 0)
    }

    @Test
    fun `include non résoluble - désalignement honnête (suite non sélectionnable)`() {
        val principal =
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <include layout="@layout/natif" />
                <Button android:text="apres" />
            </LinearLayout>
            """.trimIndent()
        // Resolveur qui ne connaît RIEN : l'include échoue → l'include
        // lui-même ne produit AUCUN élément (l'inflater gonfle nativement,
        // hors de portée de l'aligneur) et la SUITE est désarmée (jamais
        // de span deviné).
        val elements = AligneurElementsXml.aligner(principal, null)
        assertEquals("root + Button (l'include ne produit rien)", 2, elements.size)
        // Le Button APRÈS la rupture est désarmé.
        assertEquals("Button", elements[1].nomBalise)
        assertEquals(-1, elements[1].debut)
        assertEquals(-1, elements[1].fin)
        // Le root, AVANT la rupture, garde son span.
        assertTrue(elements[0].debut >= 0)
        assertTrue(elements[0].fin > elements[0].debut)
    }

    @Test
    fun `balises vides - document sans élément producteur`() {
        val elements = AligneurElementsXml.aligner("<requestFocus />", null)
        assertTrue(elements.isEmpty())
    }

    @Test
    fun `espaces et attributs exotiques - le début reste le chevron exact`() {
        // Valeur d'attribut contenant un '>' (légal en XML) : la recherche
        // arrière du '<' ne se laisse pas piéger (pas de '<' dans les valeurs).
        val doc =
            """
            <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                android:text="a > b" />
            """.trimIndent()
        val elements = AligneurElementsXml.aligner(doc, null)
        assertEquals(1, elements.size)
        assertEquals(doc.indexOf('<'), elements[0].debut)
        assertEquals(doc.length, elements[0].fin)
    }
}
