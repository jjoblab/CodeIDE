package jo.codeide.feature.ai

import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests des spans Markdown **en ligne** (mission IA, lot IA-3) : gras,
 * italique, code en ligne, lien `[texte](url)` (RÉGRESSION — l'ancien
 * balayage cherchait un second « [ » comme marqueur fermant et ne
 * rendait JAMAIS un lien), texte simple.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SpansMarkdownTest {
    @Test
    fun `le gras pose un StyleSpan BOLD sur le texte intérieur`() {
        val spanne = SpansMarkdown.de("très **important** ici")
        assertEquals("très important ici", spanne.toString())
        val spans = spanne.getSpans(0, spanne.length, StyleSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(android.graphics.Typeface.BOLD, spans[0].style)
    }

    @Test
    fun `l'italique avec astérisque simple pose ITALIC`() {
        val spanne = SpansMarkdown.de("un *mot* doux")
        assertEquals("un mot doux", spanne.toString())
        val spans = spanne.getSpans(0, spanne.length, StyleSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(android.graphics.Typeface.ITALIC, spans[0].style)
    }

    @Test
    fun `le code en ligne pose un TypefaceSpan monospace`() {
        val spanne = SpansMarkdown.de("appeler `run()` d'abord")
        assertEquals("appeler run() d'abord", spanne.toString())
        val spans = spanne.getSpans(0, spanne.length, TypefaceSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("monospace", spans[0].family)
    }

    @Test
    fun `le lien pose un ClickableSpan portant la bonne URL`() {
        var ouverte: String? = null
        LienIa.ouverture = { ouverte = it }
        try {
            val spanne = SpansMarkdown.de("voir [la doc](https://exemple.dev) svp")
            assertEquals("voir la doc svp", spanne.toString())
            val spans = spanne.getSpans(0, spanne.length, ClickableSpan::class.java)
            assertEquals(1, spans.size)
            spans[0].onClick(android.view.View(ApplicationProvider.getApplicationContext()))
            assertEquals("https://exemple.dev", ouverte)
        } finally {
            LienIa.ouverture = null
        }
    }

    @Test
    fun `deux liens sur la même ligne sont tous deux rendus`() {
        val spanne = SpansMarkdown.de("[a](https://a.dev) et [b](https://b.dev)")
        assertEquals("a et b", spanne.toString())
        assertEquals(2, spanne.getSpans(0, spanne.length, ClickableSpan::class.java).size)
    }

    @Test
    fun `un texte sans motif reste tel quel`() {
        assertEquals("texte brut (sans motif)", SpansMarkdown.de("texte brut (sans motif)").toString())
    }
}
